/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.compiler;

import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashSet;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;

import org.voltdb.VoltDB;
import org.voltdb.compiler.deploymentfile.AdminModeType;
import org.voltdb.compiler.deploymentfile.ClusterType;
import org.voltdb.compiler.deploymentfile.CommandLogType;
import org.voltdb.compiler.deploymentfile.DeploymentType;
import org.voltdb.compiler.deploymentfile.ExportType;
import org.voltdb.compiler.deploymentfile.HttpdType;
import org.voltdb.compiler.deploymentfile.HttpdType.Jsonapi;
import org.voltdb.compiler.deploymentfile.PartitionDetectionType;
import org.voltdb.compiler.deploymentfile.PartitionDetectionType.Snapshot;
import org.voltdb.compiler.deploymentfile.PathEntry;
import org.voltdb.compiler.deploymentfile.PathsType;
import org.voltdb.compiler.deploymentfile.PathsType.Voltdbroot;
import org.voltdb.compiler.deploymentfile.SecurityType;
import org.voltdb.compiler.deploymentfile.SnapshotType;
import org.voltdb.compiler.deploymentfile.SystemSettingsType;
import org.voltdb.compiler.deploymentfile.SystemSettingsType.Temptables;
import org.voltdb.compiler.deploymentfile.UsersType;
import org.voltdb.compiler.deploymentfile.UsersType.User;

public class DeploymentBuilder {
    public static final class UserInfo {
        public final String name;
        public String password;
        private final String groups[];

        public UserInfo (final String name, final String password, final String groups[]){
            this.name = name;
            this.password = password;
            this.groups = groups;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(final Object o) {
            if (o instanceof UserInfo) {
                final UserInfo oInfo = (UserInfo)o;
                return name.equals(oInfo.name);
            }
            return false;
        }
    }

    int m_hostCount = 1;
    int m_sitesPerHost = 1;
    int m_replication = 0;
    boolean m_useCustomAdmin = false;
    int m_adminPort = VoltDB.DEFAULT_ADMIN_PORT;
    boolean m_adminOnStartup = false;

    final LinkedHashSet<UserInfo> m_users = new LinkedHashSet<UserInfo>();

    // zero defaults to first open port >= 8080.
    // negative one means disabled in the deployment file.
    int m_httpdPortNo = -1;
    boolean m_jsonApiEnabled = true;

    boolean m_securityEnabled = false;

    private String m_snapshotPath = null;
    private int m_snapshotRetain = 0;
    private String m_snapshotPrefix = null;
    private String m_snapshotFrequency = null;
    private String m_voltRootPath = null;

    private boolean m_ppdEnabled = false;
    private String m_ppdPrefix = "none";

    private String m_internalSnapshotPath;
    private String m_commandLogPath;
    private Boolean m_commandLogSync;
    private Boolean m_commandLogEnabled;
    private Integer m_commandLogSize;
    private Integer m_commandLogFsyncInterval;
    private Integer m_commandLogMaxTxnsBeforeFsync;

    private Integer m_snapshotPriority;

    private Integer m_maxTempTableMemory = 100;

    String m_elloader = null;         // loader package.Classname
    private boolean m_elenabled;      // true if enabled; false if disabled

    public void configureLogging(String internalSnapshotPath, String commandLogPath, Boolean commandLogSync,
            Boolean commandLogEnabled, Integer fsyncInterval, Integer maxTxnsBeforeFsync, Integer logSize) {
        m_internalSnapshotPath = internalSnapshotPath;
        m_commandLogPath = commandLogPath;
        m_commandLogSync = commandLogSync;
        m_commandLogEnabled = commandLogEnabled;
        m_commandLogFsyncInterval = fsyncInterval;
        m_commandLogMaxTxnsBeforeFsync = maxTxnsBeforeFsync;
        m_commandLogSize = logSize;
    }

    public void setSnapshotPriority(int priority) {
        m_snapshotPriority = priority;
    }

    public void addUsers(final UserInfo users[]) {
        for (final UserInfo info : users) {
            final boolean added = m_users.add(info);
            if (!added) {
                assert(added);
            }
        }
    }

    public void setHTTPDPort(int port) {
        m_httpdPortNo = port;
    }

    public void setJSONAPIEnabled(final boolean enabled) {
        m_jsonApiEnabled = enabled;
    }

    public void setSecurityEnabled(final boolean enabled) {
        m_securityEnabled = enabled;
    }

    public void setSnapshotSettings(
            String frequency,
            int retain,
            String path,
            String prefix) {
        assert(frequency != null);
        assert(prefix != null);
        m_snapshotFrequency = frequency;
        m_snapshotRetain = retain;
        m_snapshotPrefix = prefix;
        m_snapshotPath = path;
    }

    public void setPartitionDetectionSettings(final String snapshotPath, final String ppdPrefix)
    {
        m_ppdEnabled = true;
        m_snapshotPath = snapshotPath;
        m_ppdPrefix = ppdPrefix;
    }

    public void addExport(final String loader, boolean enabled) {
        m_elloader = loader;
        m_elenabled = enabled;
    }

    public void setMaxTempTableMemory(int max)
    {
        m_maxTempTableMemory = max;
    }

    /**
     * Writes deployment.xml file to a temporary file. It is constructed from the passed parameters and the m_users
     * field.
     *
     * @param voltRoot
     * @param dinfo an instance {@link DeploymentInfo}
     * @return deployment path
     * @throws IOException
     * @throws JAXBException
     */
    public String getXML(String voltRoot) throws IOException, JAXBException {

        org.voltdb.compiler.deploymentfile.ObjectFactory factory =
            new org.voltdb.compiler.deploymentfile.ObjectFactory();

        // <deployment>
        DeploymentType deployment = factory.createDeploymentType();
        JAXBElement<DeploymentType> doc = factory.createDeployment(deployment);

        // <cluster>
        ClusterType cluster = factory.createClusterType();
        deployment.setCluster(cluster);
        cluster.setHostcount(m_hostCount);
        cluster.setSitesperhost(m_sitesPerHost);
        cluster.setKfactor(m_replication);

        // <paths>
        PathsType paths = factory.createPathsType();
        deployment.setPaths(paths);
        Voltdbroot voltdbroot = factory.createPathsTypeVoltdbroot();
        paths.setVoltdbroot(voltdbroot);
        voltdbroot.setPath(voltRoot);

        if (m_snapshotPath != null) {
            PathEntry snapshotPathElement = factory.createPathEntry();
            snapshotPathElement.setPath(m_snapshotPath);
            paths.setSnapshots(snapshotPathElement);
        }

        if (m_commandLogPath != null) {
            PathEntry commandLogPathElement = factory.createPathEntry();
            commandLogPathElement.setPath(m_commandLogPath);
            paths.setCommandlog(commandLogPathElement);
        }

        if (m_internalSnapshotPath != null) {
            PathEntry commandLogSnapshotPathElement = factory.createPathEntry();
            commandLogSnapshotPathElement.setPath(m_internalSnapshotPath);
            paths.setCommandlogsnapshot(commandLogSnapshotPathElement);
        }

        if (m_snapshotPrefix != null) {
            SnapshotType snapshot = factory.createSnapshotType();
            deployment.setSnapshot(snapshot);
            snapshot.setFrequency(m_snapshotFrequency);
            snapshot.setPrefix(m_snapshotPrefix);
            snapshot.setRetain(m_snapshotRetain);
        }

        SecurityType security = factory.createSecurityType();
        deployment.setSecurity(security);
        security.setEnabled(m_securityEnabled);

        if (m_commandLogSync != null || m_commandLogEnabled != null ||
                m_commandLogFsyncInterval != null || m_commandLogMaxTxnsBeforeFsync != null ||
                m_commandLogSize != null) {
            CommandLogType commandLogType = factory.createCommandLogType();
            if (m_commandLogSync != null) {
                commandLogType.setSynchronous(m_commandLogSync.booleanValue());
            }
            if (m_commandLogEnabled != null) {
                commandLogType.setEnabled(m_commandLogEnabled);
            }
            if (m_commandLogSize != null) {
                commandLogType.setLogsize(m_commandLogSize);
            }
            if (m_commandLogFsyncInterval != null || m_commandLogMaxTxnsBeforeFsync != null) {
                CommandLogType.Frequency frequency = factory.createCommandLogTypeFrequency();
                if (m_commandLogFsyncInterval != null) {
                    frequency.setTime(m_commandLogFsyncInterval);
                }
                if (m_commandLogMaxTxnsBeforeFsync != null) {
                    frequency.setTransactions(m_commandLogMaxTxnsBeforeFsync);
                }
                commandLogType.setFrequency(frequency);
            }
            deployment.setCommandlog(commandLogType);
        }

        // <partition-detection>/<snapshot>
        PartitionDetectionType ppd = factory.createPartitionDetectionType();
        deployment.setPartitionDetection(ppd);
        ppd.setEnabled(m_ppdEnabled);
        Snapshot ppdsnapshot = factory.createPartitionDetectionTypeSnapshot();
        ppd.setSnapshot(ppdsnapshot);
        ppdsnapshot.setPrefix(m_ppdPrefix);

        // <admin-mode>
        // can't be disabled, but only write out the non-default config if
        // requested by a test. otherwise, take the implied defaults (or
        // whatever local cluster overrides on the command line).
        if (m_useCustomAdmin) {
            AdminModeType admin = factory.createAdminModeType();
            deployment.setAdminMode(admin);
            admin.setPort(m_adminPort);
            admin.setAdminstartup(m_adminOnStartup);
        }

        // <systemsettings>
        SystemSettingsType systemSettingType = factory.createSystemSettingsType();
        Temptables temptables = factory.createSystemSettingsTypeTemptables();
        temptables.setMaxsize(m_maxTempTableMemory);
        systemSettingType.setTemptables(temptables);
        if (m_snapshotPriority != null) {
            SystemSettingsType.Snapshot snapshot = factory.createSystemSettingsTypeSnapshot();
            snapshot.setPriority(m_snapshotPriority);
            systemSettingType.setSnapshot(snapshot);
        }
        deployment.setSystemsettings(systemSettingType);

        // <users>
        if (m_users.size() > 0) {
            UsersType users = factory.createUsersType();
            deployment.setUsers(users);

            // <user>
            for (final UserInfo info : m_users) {
                User user = factory.createUsersTypeUser();
                users.getUser().add(user);
                user.setName(info.name);
                user.setPassword(info.password);

                // build up user/@groups.
                if (info.groups.length > 0) {
                    final StringBuilder groups = new StringBuilder();
                    for (final String group : info.groups) {
                        if (groups.length() > 0)
                            groups.append(",");
                        groups.append(group);
                    }
                    user.setGroups(groups.toString());
                }
            }
        }

        // <httpd>. Disabled unless port # is configured by a testcase
        HttpdType httpd = factory.createHttpdType();
        deployment.setHttpd(httpd);
        httpd.setEnabled(m_httpdPortNo != -1);
        httpd.setPort(m_httpdPortNo);
        Jsonapi json = factory.createHttpdTypeJsonapi();
        httpd.setJsonapi(json);
        json.setEnabled(m_jsonApiEnabled);

        // <export>
        ExportType export = factory.createExportType();
        deployment.setExport(export);
        export.setEnabled(m_elenabled);
        if (m_elloader != null) {
            export.setClazz(m_elloader);
        }

        // Have some yummy boilerplate!
        JAXBContext context = JAXBContext.newInstance(DeploymentType.class);

        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT,
                Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(doc, writer);
        String xml = writer.toString();

        return xml;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "";//getXML();
    }
}