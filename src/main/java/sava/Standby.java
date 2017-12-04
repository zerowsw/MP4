package sava;

import membership.MemberGroup;
import membership.MemberInfo;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class Standby implements Runnable{
    public static Logger logger = Logger.getLogger(Master.class);
    //port for receiving command from client
    public static int commandport = 8099;
    //port for transferring messages with workers
    public static int messageport = 8199;
    //port for one time message transportation with worker
    public static int onetimeport = 8299;

    private List<String> workers;

    private ServerSocket serverSocket;

    private Socket socket;

    private int superstep;

    private HashMap<Integer, Vertex> vertices;

    private HashMap<Integer, String> partition;

    private List<Message> messages;

    private String vertexClassName;


    public void run() {

        System.out.println("Successfully started the standby master");
        try {

            //read command from client
            ServerSocket serverSocket = new ServerSocket(Master.commandport);
            Socket acsocket = serverSocket.accept();
            ObjectInputStream objectInputStream = new ObjectInputStream(acsocket.getInputStream());
            vertices = (HashMap<Integer, Vertex>) objectInputStream.readObject();
            partition = (HashMap<Integer, String>) objectInputStream.readObject();
            acsocket.close();
            serverSocket.close();

            //get all the alive workers
            workers = new ArrayList<String>();
            for (Map.Entry<String, MemberInfo> entry : MemberGroup.membershipList.entrySet()) {
                MemberInfo member = entry.getValue();
                if (member.getIsActive() && !member.getIp().equals(Master.masterIP) && !member.getIp().equals(Master.standbyMaster) && !member.getIp().equals(Master.client)) {
                    workers.add(member.getIp());
                }
            }


            doIterations();


        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }


    /**
     * control computing in each super step
     * @throws IOException
     */
    private void doIterations() {
        boolean noActivity = false;
        boolean restart = false;
        boolean onetimeflag = true;

        do {
            System.out.println("Start iterations!");

            System.out.println("Waiting for messages when the master failed");

            if (onetimeflag) {
                try {
                    receiveMessages();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            //before sending messages to workers, check if any worker has failed
            List<String> currentWorkers = new ArrayList<String>();
            for (Map.Entry<String, MemberInfo> entry : MemberGroup.membershipList.entrySet()) {
                MemberInfo member = entry.getValue();
                if (member.getIsActive() && !member.getIp().equals(Master.masterIP) && !member.getIp().equals(Master.standbyMaster) && !member.getIp().equals(Master.client)) {
                    currentWorkers.add(member.getIp());
                }
            }
            if (currentWorkers.size() < workers.size()) {
                //some node has failed
                workers = currentWorkers;
                partition = partition(vertices, workers);
                messages = constructMessages(vertices);
                restart = true;

            }

            HashMap<String, List<Message>> messagePartition = reorganizeMessage(partition, messages);


            List<Socket> socketList = new ArrayList<Socket>();
            //deliver messages to worker
            try {

                boolean flag = false;
                for (String worker : workers) {

                    List<Message> messagesToWorker = messagePartition.get(worker);
                    Socket socketTW = new Socket(worker, messageport);
                    OutputStream outputStream = socketTW.getOutputStream();
                    ObjectOutputStream objects = new ObjectOutputStream(outputStream);

                    System.out.println("size of messages send to worker " + worker + " is" + messagesToWorker.size());

                    if (restart) {
                        System.out.println("Write restart command");
                        objects.writeUTF("restart");
                        objects.flush();
                        flag = true;
                    } else {
                        System.out.println("Write vertexClassName");
                        objects.writeUTF(vertexClassName);
                        objects.flush();
                    }

                    objects.writeObject(messagesToWorker);
                    objects.flush();

                    socketList.add(socketTW);
                }

                if (flag) {
                    restart = false;
                }

            } catch (IOException e) {
                System.out.println("Send message error! Restart");

                for (Socket socket : socketList) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        System.out.println("close sockets");
                    }
                }
                e.printStackTrace();
                socketList.clear();
                continue;
            }


            //clear the message list for new messages
            messages.clear();

            //receive messages from all the workers
            try {
                receiveMessages(socketList);
            }catch (IOException e) {
                System.out.print("Receive message error! Restart");

                for (Socket socket : socketList) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        System.out.println("close sockets error");
                    }
                }

                e.printStackTrace();
                socketList.clear();
                continue;
            }

            //when there are no new messages
            if (messages.size() == 0) {
                //end the calculation
                noActivity = true;

                try {
                    for (String worker : workers) {
                        Socket socketTW = new Socket(worker, messageport);
                        OutputStream outputStream = socketTW.getOutputStream();
                        ObjectOutputStream objects = new ObjectOutputStream(outputStream);
                        objects.writeUTF("finish");
                        objects.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } while (!noActivity);
    }

    /**
     * partition function, map the computing task to workers
     * @param vertices
     * @param workers
     * @return partition of graph according to workers
     */
    private HashMap<Integer, String> partition(HashMap<Integer, Vertex> vertices, List<String> workers) {

        int numberOfvertex = vertices.size();
        int numberofworkers = workers.size();
        int num = numberOfvertex / numberofworkers;
        int res = numberOfvertex % numberofworkers;

        HashMap<Integer, String> partition = new HashMap<Integer, String>();

        //List<Integer> vertexes = new ArrayList<Integer>();
        int index = 0;
        int count = 0;

        for (Map.Entry<Integer, Vertex> vertexEntry : vertices.entrySet()) {
            //vertexes.add(vertex.getVertexID());
            count++;
            if (res > 0) {
                if (count > num + 1) {
                    index++;
                    count = 1;
                    res--;
                }
            } else {
                if (count > num) {
                    index++;
                    count = 1;
                }
            }
            partition.put(vertexEntry.getKey(), workers.get(index));
        }
        //partition.put(workers.get(count), new ArrayList<Integer>(vertexes));
        return partition;
    }


    /**
     * for the first time, construct the messages according to original graph
     * @param vertices
     * @return constructed messages
     */
    private List<Message> constructMessages(HashMap<Integer, Vertex> vertices) {
        List<Message> messages = new ArrayList<Message>();
        for (Map.Entry<Integer, Vertex> vertexEntry : vertices.entrySet()) {
            List<Integer> destVertices = vertexEntry.getValue().getOutVertex();
            for (Integer outId : destVertices) {
                messages.add(new Message(vertexEntry.getKey(), outId, vertexEntry.getValue().getValue()));
                // System.out.print("[" + vertexEntry.getKey() + "," + outId + "," + vertexEntry.getValue().getValue() + "]");
            }
        }
        return messages;
    }


    /**
     * receive one time messages from all the workers
     * @throws IOException
     */
    private void receiveMessages() throws IOException {
        int count = 0;
        int size = workers.size();
        boolean flag = true;
        boolean workersFlag = true;

        ServerSocket ssocket;
        ssocket = new ServerSocket(Standby.onetimeport);


        while (flag) {
            Socket socket = ssocket.accept();

            System.out.println("Receive messages from " + socket.getInetAddress().getHostAddress().toString());

            InputStream inputStream = socket.getInputStream();
            ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
            try {
                List<Message> inMessages = (List<Message>) objectInputStream.readObject();
                messages.addAll(inMessages);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }

            count++;

            if (workersFlag) {
                workers = new ArrayList<String>();
                for (Map.Entry<String, MemberInfo> entry : MemberGroup.membershipList.entrySet()) {
                    MemberInfo member = entry.getValue();
                    if (member.getIsActive() && !member.getIp().equals(Master.masterIP) && !member.getIp().equals(Master.standbyMaster) && !member.getIp().equals(Master.client)) {
                        workers.add(member.getIp());
                    }
                }
                size = workers.size();
                workersFlag = flag;
            }

            if (count == size) {
                //when receive all the messages from workers
                flag = false;
            }
            socket.close();
        }
        ssocket.close();
    }


    /**
     * receive all new messages from workers
     * @throws IOException
     */
    private void receiveMessages(List<Socket> socketList) throws IOException {

        for (Socket socket : socketList) {
            InputStream inputStream = socket.getInputStream();
            ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
            try {
                List<Message> inMessages = (List<Message>) objectInputStream.readObject();
                messages.addAll(inMessages);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            socket.close();
        }
    }

    /**
     * divide the message to target worker
     * @param partition
     * @param messages
     * @return
     */
    private HashMap<String, List<Message>> reorganizeMessage(HashMap<Integer, String> partition, List<Message> messages) {
        HashMap<String, List<Message>> messagePartition = new HashMap<String, List<Message>>();

        for (String worker : workers) {
            messagePartition.put(worker, new ArrayList<Message>());
        }

        for (Message message : messages) {
            String worker = partition.get(message.getDestVertexID());
            messagePartition.get(worker).add(message);
        }
        return messagePartition;
    }
}
