package dk.statsbiblioteket.medieplatform.autonomous.newspaper;

import dk.statsbiblioteket.medieplatform.autonomous.Batch;
import dk.statsbiblioteket.medieplatform.autonomous.CommunicationException;
import dk.statsbiblioteket.medieplatform.autonomous.DomsEventStorage;
import dk.statsbiblioteket.medieplatform.autonomous.DomsEventStorageFactory;
import dk.statsbiblioteket.medieplatform.autonomous.Event;

import org.slf4j.Logger;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Called from shell script with arguments to create a batch object in DOMS with proper Premis event added.
 *
 * @author jrg
 */
public class CreateBatch {
    private static final String STOPPED_STATE = "Manually_stopped";
    public static Logger log = org.slf4j.LoggerFactory.getLogger(CreateBatch.class);

    /**
     * Receives the following arguments to create a batch object in DOMS:
     * Batch ID, roundtrip number, Premis agent name, URL to DOMS/Fedora, DOMS username, DOMS password,
     * URL to PID generator.
     *
     * @param args The command line arguments received from calling shell script. Explained above.
     */
    public static void main(String[] args) {
        String batchId;
        String roundTrip;
        String premisAgent;
        String domsUrl;
        String domsUser;
        String domsPass;
        String urlToPidGen;
        DomsEventStorageFactory domsEventStorageFactory = new DomsEventStorageFactory();
        DomsEventStorage domsEventClient;
        Date now = new Date();
        log.info("Entered main");
        if (args.length != 7) {
            System.out.println("Not the right amount of arguments");
            System.out.println("Receives the following arguments (in this order) to create a batch object in DOMS:");
            System.out.println(
                    "Batch ID, roundtrip number, Premis agent name, URL to DOMS/Fedora, DOMS username, DOMS password,");
            System.out.println("URL to PID generator.");
            System.exit(1);
        }
        batchId = args[0];
        roundTrip = args[1];
        premisAgent = args[2];
        domsUrl = args[3];
        domsUser = args[4];
        domsPass = args[5];
        urlToPidGen = args[6];
        domsEventStorageFactory.setFedoraLocation(domsUrl);
        domsEventStorageFactory.setUsername(domsUser);
        domsEventStorageFactory.setPassword(domsPass);
        domsEventStorageFactory.setPidGeneratorLocation(urlToPidGen);
        try {
            domsEventClient = domsEventStorageFactory.createDomsEventStorage();
            final int roundTripNumber = Integer.parseInt(roundTrip);
            doWork(batchId, roundTripNumber, premisAgent, domsEventClient, now);
        } catch (Exception e) {
            System.err.println("Failed adding event to batch, due to: " + e.getMessage());
            log.error("Caught exception: ", e);
            System.exit(1);
        }
    }

    /**
     * This will add the state Data_Received for a given batch and roundtrip.
     * This will fail if a later roundtrip exists, and also it will stop earlier roundtrips from processing,
     * should they exist.
     *
     * @param batchId The batch to register
     * @param roundTripNumber The roundtrip to register
     * @param premisAgent The string used as premis agent id
     * @param domsEventClient The doms event client used for registering events.
     * @param now The timestamp to use.
     * @throws CommunicationException On trouble registering event.
     */
    public static void doWork(String batchId, int roundTripNumber, String premisAgent, DomsEventStorage domsEventClient, Date now) throws CommunicationException {
        boolean alreadyApproved = false;
        boolean newerRoundTripAlreadyReceived = false;


        String message = "";

        List<Batch> roundtrips = domsEventClient.getAllRoundTrips(batchId);
        if(roundtrips == null) {
            roundtrips = Collections.EMPTY_LIST;
        }
        for (Batch roundtrip : roundtrips) {
            if (roundtrip.getRoundTripNumber() > roundTripNumber) {
                message  +=  "Roundtrip ("+roundtrip.getRoundTripNumber()+") is newer than this roundtrip ("+roundTripNumber+"), so this roundtrip will not be triggered here\n";
                log.warn("Not adding new batch '{}' roundtrip {} because a newer roundtrip {} exists", batchId, roundTripNumber, roundtrip.getRoundTripNumber());
                newerRoundTripAlreadyReceived = true;

            }
            if (isApproved(roundtrip)) {
                message  +=  "Roundtrip ("+roundtrip.getRoundTripNumber()+") is already approved, so this roundtrip ("+roundTripNumber+") should not be triggered here\n";
                log.warn("Stopping batch '{}' roundtrip {} because another roundtrip {} is already approved", batchId, roundTripNumber, roundtrip.getRoundTripNumber());
                alreadyApproved = true;

            }
        }
        domsEventClient.addEventToBatch(batchId, roundTripNumber, premisAgent, now, message, "Data_Received", !newerRoundTripAlreadyReceived);
        if (alreadyApproved){
            domsEventClient.addEventToBatch(batchId, roundTripNumber, premisAgent, now,
                    "Another Roundtrip is already approved, so this batch should be stopped",
                    STOPPED_STATE, true);
        } else if (!newerRoundTripAlreadyReceived){
            for (Batch roundtrip : roundtrips) {
                if (roundtrip.getRoundTripNumber() != roundTripNumber) {
                    domsEventClient.addEventToBatch(batchId, roundtrip.getRoundTripNumber(), premisAgent, now,
                                                    "Newer roundtrip (" + roundTripNumber + ") has been received, so this batch should be stopped",
                                                    STOPPED_STATE, true);
                    log.warn("Stopping processing of batch '{}' roundtrip {} because a newer roundtrip {} was received", batchId, roundtrip.getRoundTripNumber(), roundTripNumber);
                }
            }
        }
    }

    private static boolean isApproved(Batch olderRoundtrip) {
        for (Event event : olderRoundtrip.getEventList()) {
            if (event.getEventID().equals("Roundtrip_Approved") && event.isSuccess()) {
                return true;
            }
        }
        return false;
    }
}
