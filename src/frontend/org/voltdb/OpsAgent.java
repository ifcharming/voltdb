/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.json_voltpatches.JSONObject;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.BinaryPayloadMessage;
import org.voltcore.messaging.HostMessenger;
import org.voltcore.messaging.LocalObjectMessage;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.network.Connection;
import org.voltcore.utils.CoreUtils;

import org.voltdb.client.ClientResponse;
import org.voltdb.messaging.LocalMailbox;
import org.voltdb.utils.CompressionService;

/**
 * Agent responsible for collecting stats on this host.
 *
 */
// Izzy's ugh list:
// 1. Handle exceptions better, don't catch Throwable, etc
// 2. Need to refactor/split this along selector and have all of the
// per-selector processing done in their own objects.  OpsAgent should just
// be the processor for stats.  Stages:
// - First, add well-known mailboxes for SystemCatalog and SystemInformation,
// then split those pieces out.  Should be able to use a common subclass to
// keep a lot of the message distribution/aggregation, I think.
// - Next, maybe add a higher-level dispatcher that can get the cluster nodes
// to create mailboxes for selectors and make them queryable, to avoid having
// to keep adding well-known IDs for new services.
// 3. Handling of null/empty cases is bad.
public abstract class OpsAgent {

    protected static final VoltLogger hostLog = new VoltLogger("HOST");
    private static final byte JSON_PAYLOAD = 0;
    private static final byte STATS_PAYLOAD = 1;
    private static final int MAX_IN_FLIGHT_REQUESTS = 5;
    static int STATS_COLLECTION_TIMEOUT = 60 * 1000;

    private long m_nextRequestId = 0;
    private Mailbox m_mailbox;
    protected final String m_name;
    private final ScheduledThreadPoolExecutor m_es;

    private HostMessenger m_messenger;

    // Things that would be nice in the future:
    // 1. Instead of the tables to be aggregates identified by index in the
    // returned response, they should be named so it's safe if they return in
    // any order.
    // 2. Instead of guessing the number of returned tables, it would be nice
    // if the selector mapped to something that specified the number of
    // results, the call to get the stats, etc.
    //
    protected static class PendingStatsRequest {
        //private final OpsSelector selector;
        protected final String subselector;
        protected final Connection c;
        protected final long clientData;
        private int expectedStatsResponses = 0;
        protected VoltTable[] aggregateTables = null;
        protected final long startTime;
        public PendingStatsRequest(
                OpsSelector selector,
                String subselector,
                Connection c,
                long clientData,
                long startTime) {
            this.startTime = startTime;
            //this.selector = selector;
            this.subselector = subselector;
            this.c = c;
            this.clientData = clientData;
        }
    }

    private final Map<Long, PendingStatsRequest> m_pendingRequests = new HashMap<Long, PendingStatsRequest>();

    public OpsAgent(String name) {
        m_name = name;
        m_es =
            org.voltcore.utils.CoreUtils.getScheduledThreadPoolExecutor(m_name, 1,
                    CoreUtils.SMALL_STACK_SIZE);
        m_messenger = null;
    }

    abstract protected void collectStatsImpl(Connection c, long clientHandle, OpsSelector selector,
            ParameterSet params) throws Exception;

    abstract protected void handleJSONMessage(JSONObject obj) throws Exception;

    // Subclasses (like StatsAgent) which need this must override
    protected void dispatchFinalAggregations(PendingStatsRequest request)
    {
    }

    public void registerMailbox(final HostMessenger hostMessenger, final long hsId) {
        m_messenger = hostMessenger;
        m_messenger.generateMailboxId(hsId);
        m_mailbox = new LocalMailbox(hostMessenger, hsId) {
            @Override
            public void deliver(final VoltMessage message) {
                m_es.submit(new Runnable() {
                    @Override
                    public void run() {
                        handleMailboxMessage(message);
                    }
                });
            }
        };
        hostMessenger.registerMailbox(m_mailbox);
    }

    private void handleMailboxMessage(VoltMessage message) {
        try {
            if (message instanceof LocalObjectMessage) {
                LocalObjectMessage lom = (LocalObjectMessage)message;
                ((Runnable)lom.payload).run();
            } else if (message instanceof BinaryPayloadMessage) {
                BinaryPayloadMessage bpm = (BinaryPayloadMessage)message;
                byte payload[] = CompressionService.decompressBytes(bpm.m_payload);
                if (bpm.m_metadata[0] == JSON_PAYLOAD) {
                    String jsonString = new String(payload, "UTF-8");
                    JSONObject obj = new JSONObject(jsonString);
                    handleJSONMessage(obj);
                } else if (bpm.m_metadata[0] == STATS_PAYLOAD) {
                    handleStatsResponse(payload);
                }
            }
        } catch (Exception e) {
            hostLog.error("Exception processing message in OpsAgent for " + m_name + ": " + message, e);
        }

    }

    private void handleStatsResponse(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        Long requestId = buf.getLong();

        PendingStatsRequest request = m_pendingRequests.get(requestId);
        if (request == null) {
            hostLog.warn("Received a stats response for stats request " + requestId + " that no longer exists");
            return;
        }

        // The first message we receive will create the correct number of tables.  Nobody else better
        // disagree or there will be trouble here in River City.  Nobody else better add non-table
        // stuff after the responses to the returned messages or said trouble will also occur.  Ick, fragile.
        if (request.aggregateTables == null) {
            List<VoltTable> tables = new ArrayList<VoltTable>();
            while (buf.hasRemaining()) {
                final int tableLength = buf.getInt();
                int oldLimit = buf.limit();
                buf.limit(buf.position() + tableLength);
                ByteBuffer tableBuf = buf.slice();
                buf.position(buf.limit()).limit(oldLimit);
                ByteBuffer copy = ByteBuffer.allocate(tableBuf.capacity() * 2);
                copy.put(tableBuf);
                copy.limit(copy.position());
                copy.position(0);
                VoltTable vt = PrivateVoltTableFactory.createVoltTableFromBuffer( copy, false);
                tables.add(vt);
            }
            request.aggregateTables = tables.toArray(new VoltTable[tables.size()]);
        }
        else {
            for (int ii = 0; ii < request.aggregateTables.length; ii++) {
                if (buf.hasRemaining()) {
                    final int tableLength = buf.getInt();
                    int oldLimit = buf.limit();
                    buf.limit(buf.position() + tableLength);
                    ByteBuffer tableBuf = buf.slice();
                    buf.position(buf.limit()).limit(oldLimit);
                    VoltTable vt = PrivateVoltTableFactory.createVoltTableFromBuffer( tableBuf, true);
                    while (vt.advanceRow()) {
                        request.aggregateTables[ii].add(vt);
                    }
                }
            }
        }

        request.expectedStatsResponses--;
        if (request.expectedStatsResponses > 0) return;

        m_pendingRequests.remove(requestId);

        dispatchFinalAggregations(request);
        sendStatsResponse(request);
    }

    public void collectStats(final Connection c, final long clientHandle, final OpsSelector selector,
            final ParameterSet params) throws Exception
    {
        m_es.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    collectStatsImpl(c, clientHandle, selector, params);
                } catch (Exception e) {
                    hostLog.warn("Exception while attempting to collect stats", e);
                }
            }
        });
    }

    protected void distributeWork(PendingStatsRequest newRequest, JSONObject obj)
        throws Exception
    {
        if (m_pendingRequests.size() > MAX_IN_FLIGHT_REQUESTS) {
            /*
             * Defensively check for an expired request not caught
             * by timeout check. Should never happen.
             */
            Iterator<PendingStatsRequest> iter = m_pendingRequests.values().iterator();
            final long now = System.currentTimeMillis();
            boolean foundExpiredRequest = false;
            while (iter.hasNext()) {
                PendingStatsRequest psr = iter.next();
                if (now - psr.startTime > STATS_COLLECTION_TIMEOUT * 2) {
                    iter.remove();
                    foundExpiredRequest = true;
                }
            }
            if (!foundExpiredRequest) {
                sendErrorResponse(newRequest.c, ClientResponse.GRACEFUL_FAILURE,
                        "Too many pending stat requests", newRequest.clientData);
                return;
            }
        }

        final long requestId = m_nextRequestId++;
        m_pendingRequests.put(requestId, newRequest);
        m_es.schedule(new Runnable() {
            @Override
            public void run() {
                checkForRequestTimeout(requestId);
            }
        },
        STATS_COLLECTION_TIMEOUT,
        TimeUnit.MILLISECONDS);

        // selector, subselector, interval filled in by parse...
        obj.put("requestId", requestId);
        obj.put("returnAddress", m_mailbox.getHSId());
        int siteId = CoreUtils.getSiteIdFromHSId(m_mailbox.getHSId());
        byte payloadBytes[] = CompressionService.compressBytes(obj.toString(4).getBytes("UTF-8"));
        for (int hostId : m_messenger.getLiveHostIds()) {
            long agentHsId = CoreUtils.getHSIdFromHostAndSite(hostId, siteId);
            newRequest.expectedStatsResponses++;
            BinaryPayloadMessage bpm = new BinaryPayloadMessage(new byte[] {JSON_PAYLOAD}, payloadBytes);
            m_mailbox.send(agentHsId, bpm);
        }
    }

    private void checkForRequestTimeout(long requestId) {
        PendingStatsRequest psr = m_pendingRequests.remove(requestId);
        if (psr == null) {
            return;
        }
        hostLog.warn("OPS request for " + m_name + ", " + requestId + " timed out, sending error to client");

        sendErrorResponse(psr.c, ClientResponse.GRACEFUL_FAILURE,
                "OPS request hit sixty second timeout before all responses were received",
                psr.clientData);
    }

    protected void sendStatsResponse(PendingStatsRequest request) {
        byte statusCode = ClientResponse.SUCCESS;
        String statusString = null;
        /*
         * It is possible not to receive a table response if a feature is not enabled
         */
        // All of the null/empty table handling/detecting/generation sucks.  Just making it
        // work for now, not making it pretty. --izzy
        VoltTable responseTables[] = request.aggregateTables;
        if (responseTables == null || responseTables.length == 0) {
            responseTables = new VoltTable[0];
            statusCode = ClientResponse.GRACEFUL_FAILURE;
            statusString =
                "Requested info \"" + request.subselector +
                "\" is not yet available or not supported in the current configuration.";
        }

        ClientResponseImpl response =
            new ClientResponseImpl(statusCode, ClientResponse.UNINITIALIZED_APP_STATUS_CODE, null,
                    responseTables, statusString);
        response.setClientHandle(request.clientData);
        ByteBuffer buf = ByteBuffer.allocate(response.getSerializedSize() + 4);
        buf.putInt(buf.capacity() - 4);
        response.flattenToBuffer(buf).flip();
        request.c.writeStream().enqueue(buf);
    }

    protected void sendOpsResponse(VoltTable[] results, JSONObject obj) throws Exception
    {
        long requestId = obj.getLong("requestId");
        long returnAddress = obj.getLong("returnAddress");
        // Send a response with no data since the stats is not supported or not yet available
        if (results == null) {
            ByteBuffer responseBuffer = ByteBuffer.allocate(8);
            responseBuffer.putLong(requestId);
            byte responseBytes[] = CompressionService.compressBytes(responseBuffer.array());
            BinaryPayloadMessage bpm = new BinaryPayloadMessage( new byte[] {STATS_PAYLOAD}, responseBytes);
            m_mailbox.send(returnAddress, bpm);
            return;
        }

        ByteBuffer[] bufs = new ByteBuffer[results.length];
        int statbytes = 0;
        for (int i = 0; i < results.length; i++) {
            bufs[i] = results[i].getBuffer();
            bufs[i].position(0);
            statbytes += bufs[i].remaining();
        }

        ByteBuffer responseBuffer = ByteBuffer.allocate(
                8 + // requestId
                4 * results.length + // length prefix for each stats table
                + statbytes);
        responseBuffer.putLong(requestId);
        for (int i = 0; i < bufs.length; i++) {
            responseBuffer.putInt(bufs[i].remaining());
            responseBuffer.put(bufs[i]);
        }
        byte responseBytes[] = CompressionService.compressBytes(responseBuffer.array());

        BinaryPayloadMessage bpm = new BinaryPayloadMessage( new byte[] {STATS_PAYLOAD}, responseBytes);
        m_mailbox.send(returnAddress, bpm);
    }

    public void shutdown() throws InterruptedException {
        m_es.shutdown();
        m_es.awaitTermination(1, TimeUnit.DAYS);
    }

    protected void sendErrorResponse(Connection c, byte status, String reason, long handle)
    {
        ClientResponseImpl errorResponse = new ClientResponseImpl(status, new VoltTable[0], reason, handle);
        ByteBuffer buf = ByteBuffer.allocate(errorResponse.getSerializedSize() + 4);
        buf.putInt(buf.capacity() - 4);
        errorResponse.flattenToBuffer(buf).flip();
        c.writeStream().enqueue(buf);
        return;
    }
}
