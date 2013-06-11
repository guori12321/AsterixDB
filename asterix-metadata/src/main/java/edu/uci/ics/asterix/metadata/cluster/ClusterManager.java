package edu.uci.ics.asterix.metadata.cluster;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import edu.uci.ics.asterix.common.config.AsterixExternalProperties;
import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.event.management.AsterixEventServiceClient;
import edu.uci.ics.asterix.event.model.AsterixInstance;
import edu.uci.ics.asterix.event.schema.cluster.Cluster;
import edu.uci.ics.asterix.event.schema.cluster.Node;
import edu.uci.ics.asterix.event.schema.pattern.Pattern;
import edu.uci.ics.asterix.event.schema.pattern.Patterns;
import edu.uci.ics.asterix.event.service.AsterixEventService;
import edu.uci.ics.asterix.event.service.AsterixEventServiceUtil;
import edu.uci.ics.asterix.event.service.ILookupService;
import edu.uci.ics.asterix.event.service.ServiceProvider;
import edu.uci.ics.asterix.event.util.PatternCreator;
import edu.uci.ics.asterix.installer.schema.conf.Configuration;
import edu.uci.ics.asterix.metadata.api.IClusterEventsSubscriber;
import edu.uci.ics.asterix.metadata.api.IClusterManager;
import edu.uci.ics.asterix.om.util.AsterixAppContextInfo;
import edu.uci.ics.asterix.om.util.AsterixClusterProperties;

public class ClusterManager implements IClusterManager {

    private static final Logger LOGGER = Logger.getLogger(AsterixEventServiceClient.class.getName());

    public static ClusterManager INSTANCE = new ClusterManager();

    private static String eventsDir = System.getenv("user.dir") + File.separator + "eventrix";

    private static AsterixEventServiceClient client;

    private static ILookupService lookupService;

    private static final Set<IClusterEventsSubscriber> eventSubscribers = new HashSet<IClusterEventsSubscriber>();

    private ClusterManager() {
        Cluster asterixCluster = AsterixClusterProperties.INSTANCE.getCluster();
        String asterixDir = System.getProperty("user.dir") + File.separator + "asterix";
        String eventHome = asterixCluster.getWorkingDir().getDir();
        File configFile = new File(System.getProperty("user.dir") + File.separator + "configuration.xml");
        Configuration configuration = null;

        try {
            JAXBContext configCtx = JAXBContext.newInstance(Configuration.class);
            Unmarshaller unmarshaller = configCtx.createUnmarshaller();
            configuration = (Configuration) unmarshaller.unmarshal(configFile);
            AsterixEventService.initialize(configuration, asterixDir, eventHome);
            client = AsterixEventService.getAsterixEventServiceClient(AsterixClusterProperties.INSTANCE.getCluster());

            lookupService = ServiceProvider.INSTANCE.getLookupService();
            if (!lookupService.isRunning(configuration)) {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Lookup service not running. Starting lookup service ...");
                }
                lookupService.startService(configuration);
            } else {
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info("Lookup service running");
                }
            }

        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize cluster manager" + e);
        }
    }

    @Override
    public void addNode(Node node) throws AsterixException {
        try {
            Cluster cluster = AsterixClusterProperties.INSTANCE.getCluster();
            List<Pattern> pattern = new ArrayList<Pattern>();
            String asterixInstanceName = AsterixAppContextInfo.getInstance().getMetadataProperties().getInstanceName();
            Patterns prepareNode = PatternCreator.INSTANCE.createPrepareNodePattern(asterixInstanceName,
                    AsterixClusterProperties.INSTANCE.getCluster(), node);
            cluster.getNode().add(node);
            client.submit(prepareNode);

            AsterixExternalProperties externalProps = AsterixAppContextInfo.getInstance().getExternalProperties();
            AsterixEventServiceUtil.poulateClusterEnvironmentProperties(cluster, externalProps.getCCJavaParams(),
                    externalProps.getNCJavaParams());

            pattern.clear();
            String ccHost = cluster.getMasterNode().getClusterIp();
            String hostId = node.getId();
            String nodeControllerId = asterixInstanceName + "_" + node.getId();
            String iodevices = node.getIodevices() == null ? cluster.getIodevices() : node.getIodevices();
            Pattern startNC = PatternCreator.INSTANCE.createNCStartPattern(ccHost, hostId, nodeControllerId, iodevices);
            pattern.add(startNC);
            Patterns startNCPattern = new Patterns(pattern);
            client.submit(startNCPattern);

            AsterixInstance instance = lookupService.getAsterixInstance(cluster.getInstanceName());
            instance.getCluster().getNode().add(node);
            instance.getCluster().getSubstituteNodes().getNode().remove(node);
            lookupService.updateAsterixInstance(instance);

        } catch (Exception e) {
            throw new AsterixException(e);
        }

    }

    @Override
    public void removeNode(Node node) throws AsterixException {

    }

    private List<Pattern> getRemoveNodePattern(Node node) {
        List<Pattern> pattern = new ArrayList<Pattern>();
        return pattern;
    }

    @Override
    public void registerSubscriber(IClusterEventsSubscriber subscriber) {
        eventSubscribers.add(subscriber);
    }

    @Override
    public boolean deregisterSubscriber(IClusterEventsSubscriber subscriber) {
        return eventSubscribers.remove(subscriber);
    }

    @Override
    public Set<IClusterEventsSubscriber> getRegisteredClusterEventSubscribers() {
        return eventSubscribers;
    }
}
