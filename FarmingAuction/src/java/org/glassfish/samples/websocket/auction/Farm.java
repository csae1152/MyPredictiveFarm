package org.glassfish.samples.websocket.auction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.websocket.EncodeException;
import javax.websocket.Session;

import org.glassfish.samples.websocket.auction.message.AuctionMessage;

/**
 * Implements the auction protocol.
 *
 * @author Helmut Steiner
 */
public class Farm {

    /*
     * Current state of the actual farm.
     */
    private AuctionState state;

    /*
     * ID of the auction used for communication
     */
    private final String id;

    /*
     * Assigns id to newly created Farm object
     */
    private static int idCounter = 0;

    /*
     * Farm Item
     */
    private final FarmDevices item;

    /*
     * List of remote clients (Peers)
     */
    private final List<Session> arcList = new ArrayList<>();

    /*
     * Timer that sends pre-auction time broadcasts
     */
    private Timer auctionRunningTimer;

    /*
     * Value of the highest bid
     */
    private double bestBid;

    private String bestBidderName;

    /*
     * Separator used to separate different fields in the communication
     * datastring
     */
    public static final String SEPARATOR = ":";

    public enum AuctionState {
        PRE_AUCTION, AUCTION_RUNNING, AUCTION_FINISHED
    }


    public Farm(FarmDevices item) {
        this.item = item;

        this.state = AuctionState.PRE_AUCTION;
        this.id = Integer.toString(Farm.idCounter);
        bestBid = item.getPrice();
        idCounter++;
    }

    synchronized void addArc(Session arc) {
        arcList.add(arc);
    }

    public synchronized void removeArc(Session arc) {
        arcList.remove(arc);
    }

    /*
     * New user logs into the auction.
     */
    public void handleLoginRequest(AuctionMessage messsage, Session arc) {

        arc.getUserProperties().put("name", messsage.getData());
        synchronized (id) {
            if (state != AuctionState.AUCTION_FINISHED) {
                if (!getRemoteClients().contains(arc)) {
                    this.addArc(arc);
                }
                try {
                    item.setPrice(bestBid);
                    arc.getBasicRemote().sendObject(new AuctionMessage.LoginResponseMessage(id, item));
                } catch (IOException | EncodeException e) {
                    Logger.getLogger(Farm.class.getName()).log(Level.SEVERE, null, e);
                }

                if(state == AuctionState.PRE_AUCTION){
                    startAuctionTimeBroadcast();
                }
            } else {
                try {
                    arc.getBasicRemote().sendObject(new AuctionMessage.LoginResponseMessage(id, item));
                    if(bestBidderName!= null && bestBidderName.equals(messsage.getData())){
                        arc.getBasicRemote().sendObject(new AuctionMessage.ResultMessage(id, String.format("Congratulations, You won the auction and will pay %.0f.", bestBid)));
                    }else{
                        arc.getBasicRemote().sendObject(new AuctionMessage.ResultMessage(id, String.format("You did not win the auction. The item was sold for %.0f.", bestBid)));
                    }
                } catch (IOException | EncodeException e) {
                    Logger.getLogger(Farm.class.getName()).log(Level.SEVERE, null, e);
                }
            }
        }
    }

    public void handleBidRequest(AuctionMessage message, Session arc) {
        synchronized (id) {
            if (state == AuctionState.AUCTION_RUNNING) {
                Double bid = Double.parseDouble((String)message.getData());
                if (bid > bestBid) {
                    bestBid = bid;

                    bestBidderName = (String) arc.getUserProperties().get("name");
                    sendPriceUpdateMessage();
                    stopAuctionTimeBroadcast();
                    startAuctionTimeBroadcast();
                }
            }
        }
    }

    private void sendPriceUpdateMessage() {
        AuctionMessage.PriceUpdateResponseMessage purm = new AuctionMessage.PriceUpdateResponseMessage(id, "" + bestBid);
        for (Session arc : getRemoteClients()) {
            try {
                arc.getBasicRemote().sendObject(purm);
            } catch (IOException | EncodeException e) {
                Logger.getLogger(Farm.class.getName()).log(Level.SEVERE, null, e);
            }
        }
    }

    public void switchStateToAuctionFinished() {
        synchronized (id) {
            state = AuctionState.AUCTION_FINISHED;
        }
        stopAuctionTimeBroadcast();
        sendAuctionResults();
    }

    private void sendAuctionResults() {
        Session bestBidder = null;

        if(bestBidderName != null){
            for (Session session : getRemoteClients()) {
                if(session.getUserProperties().get("name").equals(bestBidderName)){
                    bestBidder = session;
                }
            }
        }

        if (bestBidder!= null) {
            AuctionMessage.ResultMessage winnerMessage = new AuctionMessage.ResultMessage(id, String.format("Congratulations, You won the auction and will pay %.0f.", bestBid));
            try {
                bestBidder.getBasicRemote().sendObject(winnerMessage);
            } catch (IOException | EncodeException e) {
                Logger.getLogger(Farm.class.getName()).log(Level.SEVERE, null, e);
            }
        }

        AuctionMessage.ResultMessage loserMessage = new AuctionMessage.ResultMessage(id, String.format("You did not win the auction. The item was sold for %.0f.", bestBid));
        for (Session arc : getRemoteClients()) {
            if (arc != bestBidder) {
                try {
                    arc.getBasicRemote().sendObject(loserMessage);
                } catch (IOException | EncodeException e) {
                    Logger.getLogger(Farm.class.getName()).log(Level.SEVERE, null, e);
                }
            }
        }
    }

    private void startAuctionTimeBroadcast() {
        synchronized (id) {
            state = AuctionState.AUCTION_RUNNING;
        }
        auctionRunningTimer = new Timer();
        auctionRunningTimer.schedule(new AuctionTimeBroadcasterTask(this, item.getBidTimeoutS()), 0, 1000);

    }

    private void stopAuctionTimeBroadcast() {
        auctionRunningTimer.cancel();
    }

    public String getId() {
        return id;
    }

    public List<Session> getRemoteClients() {
        return Collections.unmodifiableList(arcList);
    }

    public FarmDevices getItem() {
        return item;
    }
}
