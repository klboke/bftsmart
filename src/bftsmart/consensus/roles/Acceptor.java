/**
Copyright (c) 2007-2013 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and the authors indicated in the @author tags

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.consensus.roles;


import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.communication.server.ServerConnection;
import bftsmart.consensus.Consensus;
import bftsmart.consensus.Epoch;
import bftsmart.consensus.app.BatchAppResult;
import bftsmart.consensus.app.ErrorCode;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.consensus.messages.MessageFactory;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.tom.core.ExecutionManager;
import bftsmart.tom.core.ReplyManager;
import bftsmart.tom.core.TOMLayer;
import bftsmart.tom.core.messages.TOMMessage;
import bftsmart.tom.core.messages.TOMMessageType;
import bftsmart.tom.server.Replier;
import bftsmart.tom.server.defaultservices.DefaultRecoverable;
import bftsmart.tom.util.Logger;
import bftsmart.tom.util.TOMUtil;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * This class represents the acceptor role in the consensus protocol.
 * This class work together with the TOMLayer class in order to
 * supply a atomic multicast service.
 *
 * @author Alysson Bessani
 */
public final class Acceptor {

    private int me; // This replica ID
    private ExecutionManager executionManager; // Execution manager of consensus's executions
    private MessageFactory factory; // Factory for PaW messages
    private ServerCommunicationSystem communication; // Replicas comunication system
    private TOMLayer tomLayer; // TOM layer
    private ServerViewController controller;
    //private Cipher cipher;
    private Mac mac;
    private static org.slf4j.Logger LOGGER = LoggerFactory.getLogger(Acceptor.class);

    /**
     * Creates a new instance of Acceptor.
     * @param communication Replicas communication system
     * @param factory Message factory for PaW messages
     * @param controller
     */
    public Acceptor(ServerCommunicationSystem communication, MessageFactory factory, ServerViewController controller) {
        this.communication = communication;
        this.me = controller.getStaticConf().getProcessId();
        this.factory = factory;
        this.controller = controller;
        try {
            //this.cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            //this.cipher = Cipher.getInstance(ServerConnection.MAC_ALGORITHM);
            this.mac = Mac.getInstance(ServerConnection.MAC_ALGORITHM);
        } catch (NoSuchAlgorithmException /*| NoSuchPaddingException*/ ex) {
            ex.printStackTrace();
        }
    }

    public DefaultRecoverable getDefaultExecutor() {
        return (DefaultRecoverable) tomLayer.getDeliveryThread().getReceiver().getExecutor();
    }

    public Replier getBatchReplier() {
        return tomLayer.getDeliveryThread().getReceiver().getReplier();
    }

    public ReplyManager getReplyManager() {
        return tomLayer.getDeliveryThread().getReceiver().getRepMan();
    }

    public MessageFactory getFactory() {
        return factory;
    }

    /**
     * Sets the execution manager for this acceptor
     * @param manager Execution manager for this acceptor
     */
    public void setExecutionManager(ExecutionManager manager) {
        this.executionManager = manager;
    }

    /**
     * Sets the TOM layer for this acceptor
     * @param tom TOM layer for this acceptor
     */
    public void setTOMLayer(TOMLayer tom) {
        this.tomLayer = tom;
    }

    /**
     * Called by communication layer to delivery Paxos messages. This method
     * only verifies if the message can be executed and calls process message
     * (storing it on an out of context message buffer if this is not the case)
     *
     * @param msg Paxos messages delivered by the communication layer
     */
    public final void deliver(ConsensusMessage msg) {
        if (executionManager.checkLimits(msg)) {
//            Logger.println("processing paxos msg with id " + msg.getNumber());
            LOGGER.debug("processing paxos msg with id {}",msg.getNumber());
            processMessage(msg);
        } else {
//            Logger.println("out of context msg with id " + msg.getNumber());
            LOGGER.debug("out of context msg with id {}",msg.getNumber());
            tomLayer.processOutOfContext();
        }
    }
   
    /**
     * Called when a Consensus message is received or when a out of context message must be processed.
     * It processes the received message according to its type
     *
     * @param msg The message to be processed
     */
    public final void processMessage(ConsensusMessage msg) {
        Consensus consensus = executionManager.getConsensus(msg.getNumber());

        consensus.lock.lock();
        Epoch epoch = consensus.getEpoch(msg.getEpoch(), controller);
        switch (msg.getType()){
            case MessageFactory.PROPOSE:{
                    proposeReceived(epoch, msg);
            }break;
            case MessageFactory.WRITE:{
                    writeReceived(epoch, msg.getSender(), msg.getValue());
            }break;
            case MessageFactory.ACCEPT:{
                    acceptReceived(epoch, msg);
            }
        }
        consensus.lock.unlock();
    }

    /**
     * Called when a PROPOSE message is received or when processing a formerly out of context propose which
     * is know belongs to the current consensus.
     *
     * @param msg The PROPOSE message to by processed
     */
    public void proposeReceived(Epoch epoch, ConsensusMessage msg) {
        int cid = epoch.getConsensus().getId();
        int ts = epoch.getConsensus().getEts();
        int ets = executionManager.getConsensus(msg.getNumber()).getEts();
//    	Logger.println("(Acceptor.proposeReceived) PROPOSE for consensus " + cid);

    	LOGGER.debug("(Acceptor.proposeReceived) PROPOSE for consensus {} ", cid);
    	if (msg.getSender() == executionManager.getCurrentLeader() // Is the replica the leader?
                && epoch.getTimestamp() == 0 && ts == ets && ets == 0) { // Is all this in epoch 0?
    		executePropose(epoch, msg.getValue());
    	} else {
//    		Logger.println("Propose received is not from the expected leader");
    		LOGGER.debug("Propose received is not from the expected leader");
    	}
    }

    /**
     * Executes actions related to a proposed value.
     *
     * @param epoch the current epoch of the consensus
     * @param value Value that is proposed
     */
    private void executePropose(Epoch epoch, byte[] value) {
        int cid = epoch.getConsensus().getId();
        Logger.println("(Acceptor.executePropose) executing propose for " + cid + "," + epoch.getTimestamp());

        long consensusStartTime = System.nanoTime();

        
        if(epoch.propValue == null) { //only accept one propose per epoch
            epoch.propValue = value;
            epoch.propValueHash = tomLayer.computeHash(value);
            
            /*** LEADER CHANGE CODE ********/
            epoch.getConsensus().addWritten(value);
            Logger.println("(Acceptor.executePropose) I have written value " + Arrays.toString(epoch.propValueHash) + " in consensus instance " + cid + " with timestamp " + epoch.getConsensus().getEts());
            /*****************************************/

            //start this consensus if it is not already running
            if (cid == tomLayer.getLastExec() + 1) {
                tomLayer.setInExec(cid);
            }
            epoch.deserializedPropValue = tomLayer.checkProposedValue(value, true);

            if (epoch.deserializedPropValue != null && !epoch.isWriteSetted(me)) {
                if(epoch.getConsensus().getDecision().firstMessageProposed == null) {
                    epoch.getConsensus().getDecision().firstMessageProposed = epoch.deserializedPropValue[0];
                }
                if (epoch.getConsensus().getDecision().firstMessageProposed.consensusStartTime == 0) {
                    epoch.getConsensus().getDecision().firstMessageProposed.consensusStartTime = consensusStartTime;
                    
                }
                epoch.getConsensus().getDecision().firstMessageProposed.proposeReceivedTime = System.nanoTime();
                
                if(controller.getStaticConf().isBFT()){
                    Logger.println("(Acceptor.executePropose) sending WRITE for " + cid);

                    epoch.setWrite(me, epoch.propValueHash);
                    epoch.getConsensus().getDecision().firstMessageProposed.writeSentTime = System.nanoTime();
                    communication.send(this.controller.getCurrentViewOtherAcceptors(),
                            factory.createWrite(cid, epoch.getTimestamp(), epoch.propValueHash));

                    Logger.println("(Acceptor.executePropose) WRITE sent for " + cid);
                
                    computeWrite(cid, epoch, epoch.propValueHash);
                
                    Logger.println("(Acceptor.executePropose) WRITE computed for " + cid);
                
                } else {
                 	epoch.setAccept(me, epoch.propValueHash);
                 	epoch.getConsensus().getDecision().firstMessageProposed.writeSentTime = System.nanoTime();
                        epoch.getConsensus().getDecision().firstMessageProposed.acceptSentTime = System.nanoTime();
                 	/**** LEADER CHANGE CODE! ******/
                        Logger.println("(Acceptor.executePropose) [CFT Mode] Setting consensus " + cid + " QuorumWrite tiemstamp to " + epoch.getConsensus().getEts() + " and value " + Arrays.toString(epoch.propValueHash));
 	                epoch.getConsensus().setQuorumWrites(epoch.propValueHash);
 	                /*****************************************/

                        communication.send(this.controller.getCurrentViewOtherAcceptors(),
 	                    factory.createAccept(cid, epoch.getTimestamp(), epoch.propValueHash));

                        computeAccept(cid, epoch, epoch.propValueHash);
                }
                executionManager.processOutOfContext(epoch.getConsensus());
            }
        } 
    }

    /**
     * Called when a WRITE message is received
     *
     * @param epoch Epoch of the receives message
     * @param a Replica that sent the message
     * @param value Value sent in the message
     */
    private void writeReceived(Epoch epoch, int a, byte[] value) {
        int cid = epoch.getConsensus().getId();
        Logger.println("(Acceptor.writeAcceptReceived) WRITE from " + a + " for consensus " + cid);
        epoch.setWrite(a, value);

        computeWrite(cid, epoch, value);
    }

    /**
     * merge byte array
     * @param prop serialized prop value
     * @param appHash app hash vaule
     * @return
     */
    public byte[] MergeByte(byte[] prop, byte[] appHash){
        byte[] result = new byte[prop.length + appHash.length];
        System.arraycopy(prop, 0, result, 0, prop.length);
        System.arraycopy(appHash, 0, result, prop.length, appHash.length);
        return result;
    }

    /**
     * Computes WRITE values according to Byzantine consensus specification
     * values received).
     *
     * @param cid Consensus ID of the received message
     * @param epoch Epoch of the receives message
     * @param value Value sent in the message
     */
    private void computeWrite(int cid, Epoch epoch, byte[] value) {
        int writeAccepted = epoch.countWrite(value);
        
        Logger.println("(Acceptor.computeWrite) I have " + writeAccepted +
                " WRITEs for " + cid + "," + epoch.getTimestamp());

        if (writeAccepted > controller.getQuorum() && Arrays.equals(value, epoch.propValueHash)) {
                        
            if (!epoch.isAcceptSetted(me)) {

                Logger.println("(Acceptor.computeWrite) sending WRITE for " + cid);

                /**** LEADER CHANGE CODE! ******/
                Logger.println("(Acceptor.computeWrite) Setting consensus " + cid + " QuorumWrite tiemstamp to " + epoch.getConsensus().getEts() + " and value " + Arrays.toString(value));
                epoch.getConsensus().setQuorumWrites(value);
                /*****************************************/

                if(epoch.getConsensus().getDecision().firstMessageProposed!=null) {

                        epoch.getConsensus().getDecision().firstMessageProposed.acceptSentTime = System.nanoTime();
                }

                // add to implement application consistency
                if(controller.getStaticConf().isBFT()){

                    DefaultRecoverable defaultExecutor = getDefaultExecutor();
                    byte[][] commands = new byte[epoch.deserializedPropValue.length][];

                    for (int i = 0; i < epoch.deserializedPropValue.length; i++) {
                        commands[i] = epoch.deserializedPropValue[i].getContent();
                    }

                    BatchAppResult appHashResult = defaultExecutor.preComputeHash(commands);

                    byte[] result = MergeByte(epoch.propValue, appHashResult.getAppHashBytes());

                    epoch.propAndAppValue = result;

                    epoch.propAndAppValueHash = tomLayer.computeHash(result);

                    epoch.preComputeRes = appHashResult.getErrprCode();

                    tomLayer.getExecManager().getConsensus(tomLayer.getInExec()).setPrecomputed(true);

                    epoch.setAsyncResponseLinkedList(appHashResult.getAsyncResponseLinkedList());

                    epoch.batchId = appHashResult.getBatchId();

                    epoch.setAccept(me, epoch.propAndAppValueHash);

                    ConsensusMessage cm = factory.createAccept(cid, epoch.getTimestamp(), epoch.propAndAppValueHash);

                    //add origin propose hash for accept type consensus msg
                    cm.setOrigPropValue(epoch.propValueHash);

                    // Create a cryptographic proof for this ACCEPT message
                    Logger.println("(Acceptor.computeWrite) Creating cryptographic proof for my ACCEPT message from consensus " + cid);
                    insertProof(cm, epoch);

                    int[] targets = this.controller.getCurrentViewOtherAcceptors();
                    communication.getServersConn().send(targets, cm, true);

                    epoch.addToProof(cm);
                    computeAccept(cid, epoch, epoch.propAndAppValueHash);
                }
                else {
                    epoch.setAccept(me, value);

                    ConsensusMessage cm = factory.createAccept(cid, epoch.getTimestamp(), value);

                    // Create a cryptographic proof for this ACCEPT message
                    Logger.println("(Acceptor.computeWrite) Creating cryptographic proof for my ACCEPT message from consensus " + cid);
                    insertProof(cm, epoch);

                    int[] targets = this.controller.getCurrentViewOtherAcceptors();
                    communication.getServersConn().send(targets, cm, true);

                    //communication.send(this.reconfManager.getCurrentViewOtherAcceptors(),
                    //factory.createStrong(cid, epoch.getNumber(), value));
                    epoch.addToProof(cm);
                    computeAccept(cid, epoch, value);

                }
            }
        }
    }

    /**
     * Create a cryptographic proof for a consensus message
     * 
     * This method modifies the consensus message passed as an argument,
     * so that it contains a cryptographic proof.
     * 
     * @param cm The consensus message to which the proof shall be set
     * @param epoch The epoch during in which the consensus message was created
     */
    private void insertProof(ConsensusMessage cm, Epoch epoch) {
        ByteArrayOutputStream bOut = new ByteArrayOutputStream(248);
        try {
            new ObjectOutputStream(bOut).writeObject(cm);
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        byte[] data = bOut.toByteArray();

        // check if consensus contains reconfiguration request
        TOMMessage[] msgs = epoch.deserializedPropValue;
        boolean hasReconf = false;

        for (TOMMessage msg : msgs) {
            if (msg.getReqType() == TOMMessageType.RECONFIG
                    && msg.getViewID() == controller.getCurrentViewId()) {
                hasReconf = true;
                break; // no need to continue, exit the loop
            }
        }

        //If this consensus contains a reconfiguration request, we need to use
        // signatures (there might be replicas that will not be part of the next
        //consensus instance, and so their MAC will be outdated and useless)
        if (hasReconf) {

            PrivateKey RSAprivKey = controller.getStaticConf().getRSAPrivateKey();

            byte[] signature = TOMUtil.signMessage(RSAprivKey, data);

            cm.setProof(signature);

        } else { //... if not, we can use MAC vectores
            int[] processes = this.controller.getCurrentViewAcceptors();

            HashMap<Integer, byte[]> macVector = new HashMap<>();

            for (int id : processes) {

                try {

                    SecretKey key = null;
                    do {
                        key = communication.getServersConn().getSecretKey(id);
                        if (key == null) {
                            System.out.println("(Acceptor.insertProof) I don't have yet a secret key with " + id + ". Retrying.");
                            Thread.sleep(1000);
                        }

                    } while (key == null);  // JCS: This loop is to solve a race condition where a
                                            // replica might have already been insert in the view or
                                            // recovered after a crash, but it still did not concluded
                                            // the diffie helman protocol. Not an elegant solution,
                                            // but for now it will do
                    this.mac.init(key);
                    macVector.put(id, this.mac.doFinal(data));
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                } catch (InvalidKeyException ex) {

                    System.out.println("Problem with secret key from " + id);
                    ex.printStackTrace();
                }
            }

            cm.setProof(macVector);
        }
        
    }
    
    /**
     * Called when a ACCEPT message is received
     * @param epoch Epoch of the receives message
     * @param a Replica that sent the message
     * @param value Value sent in the message
     */
    private void acceptReceived(Epoch epoch, ConsensusMessage msg) {
        int cid = epoch.getConsensus().getId();
        Logger.println("(Acceptor.acceptReceived) ACCEPT from " + msg.getSender() + " for consensus " + cid);
        epoch.setAccept(msg.getSender(), msg.getValue());
        epoch.addToProof(msg);

        computeAccept(cid, epoch, msg.getValue());
    }

    /**
     * Computes ACCEPT values according to the Byzantine consensus
     * specification
     * @param epoch Epoch of the receives message
     * @param value Value sent in the message
     */
    private void computeAccept(int cid, Epoch epoch, byte[] value) {
        Logger.println("(Acceptor.computeAccept) I have " + epoch.countAccept(value) +
                " ACCEPTs for " + cid + "," + epoch.getTimestamp());

        if (epoch.countAccept(value) > controller.getQuorum() && !epoch.getConsensus().isDecided()) {
            if (Arrays.equals(value, epoch.propAndAppValueHash)) {

                LOGGER.debug("(Acceptor.computeAccept) Deciding {} ", cid);
                decide(epoch);

            }
            else {
                //Leader does evil to me only, need to roll back

                System.out.println("Quorum is satisfied, but leader maybe do evil, will goto pre compute rollback branch!");

                TOMMessage[] requests = epoch.deserializedPropValue;

                tomLayer.clientsManager.requestsPending(requests);

                // rollback
                getDefaultExecutor().preComputeRollback(epoch.getBatchId());

                tomLayer.setLastExec(tomLayer.getInExec());

                //This round of consensus has been rolled back, mark it
                tomLayer.execManager.updateConsensus(tomLayer.getInExec());

                tomLayer.setInExec(-1);

                //Pause processing of new messages, Waiting for trigger state transfer
                tomLayer.requestsTimer.Enabled(false);
                tomLayer.requestsTimer.stopTimer();

                if (!tomLayer.execManager.stopped()) {
                    tomLayer.execManager.stop();
                }
            }
            return;
        }

        // rollback
        if (((epoch.countAcceptSetted() == controller.getCurrentViewN()) && (epoch.countAccept(value) < controller.getQuorum() + 1))
                || ((epoch.countAcceptSetted() > 2f) && (epoch.countAccept(value) < controller.getCurrentViewF() + 1)
                     && (epoch.maxSameValueCount() < controller.getCurrentViewF() + 1))) {

            TOMMessage[] requests = epoch.deserializedPropValue;

            tomLayer.clientsManager.requestsPending(requests);

            try {

                // reply

                List<byte[]> updatedResp;

                if (ErrorCode.valueOf(epoch.getPreComputeRes()) == ErrorCode.PRECOMPUTE_SUCC) {
                    // update exe state to IGNORED_BY_CONSENSUS_PHASE_PRECOMPUTE_ROLLBACK
                    System.out.println("Quorum is not satisfied, node's pre compute hash is inconsistent, will goto pre compute rollback phase!");
                    updatedResp = getDefaultExecutor().updateResponses(epoch.getAsyncResponseLinkedList());

                } else {
                    //keep pre compute fail exe state
                    System.out.println("Quorum is not satisfied, node's order transactions exe error, will goto pre compute rollback phase!");
                    updatedResp = epoch.getAsyncResponseLinkedList();
                }

                Replier replier = getBatchReplier();

                ReplyManager repMan = getReplyManager();

                for (int index = 0; index < requests.length; index++) {
                    TOMMessage request = requests[index];
                    request.reply = new TOMMessage(me, request.getSession(), request.getSequence(),
                            request.getOperationId(), updatedResp.get(index), controller.getCurrentViewId(),
                            request.getReqType());

                    if (controller.getStaticConf().getNumRepliers() > 0) {
                        bftsmart.tom.util.Logger.println("(ServiceReplica.receiveMessages) sending reply to "
                                + request.getSender() + " with sequence number " + request.getSequence()
                                + " and operation ID " + request.getOperationId() + " via ReplyManager");
                        repMan.send(request);
                    } else {
                        bftsmart.tom.util.Logger.println("(ServiceReplica.receiveMessages) sending reply to "
                                + request.getSender() + " with sequence number " + request.getSequence()
                                + " and operation ID " + request.getOperationId());
                        replier.manageReply(request, null);
                        // cs.send(new int[]{request.getSender()}, request.reply);
                    }
                }

            } finally {

                // rollback
                if (ErrorCode.valueOf(epoch.getPreComputeRes()) == ErrorCode.PRECOMPUTE_SUCC) {
                    getDefaultExecutor().preComputeRollback(epoch.getBatchId());
                }

                tomLayer.setLastExec(tomLayer.getInExec());

                //This round of consensus has been rolled back, mark it
//                tomLayer.execManager.updateConsensus(tomLayer.getInExec());

                tomLayer.setInExec(-1);
            }
        }
    }

    /**
     * This is the method invoked when a value is decided by this process
     * @param epoch Epoch at which the decision is made
     */
    private void decide(Epoch epoch) {
        if (epoch.getConsensus().getDecision().firstMessageProposed != null)
            epoch.getConsensus().getDecision().firstMessageProposed.decisionTime = System.nanoTime();

        epoch.getConsensus().decided(epoch, true);
    }

}
