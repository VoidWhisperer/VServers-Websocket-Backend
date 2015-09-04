package net.voksul.vservers_websocket;

import com.mongodb.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;

/**
 * Created by Chris on 5/27/2015.
 */
public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {
        final String serverName = "servers.voksul.net"; //This should be the IP or DNS name where you plan to host the server
        final int port = 25054; //The port that the client websocket sohuld connect to.
        final WebSocketServ server = new WebSocketServ(new InetSocketAddress(serverName,port));
        new Thread(new Runnable() {
            public void run() {
                server.run();
            }
        }).start(); //This is done this way to make sure that the server application doesn't stall immediatly after starting the websocket server, so it runs the websocket server on a new thread.

        DB vservers = server.mongo.getDB("vservers");
        DBCollection servers = vservers.getCollection("servers");

        //The code below runs through the currently active servers and expires them if they have run past their alloted run time, which in this case is approximately 1 hour.
        while(true)
        {
            DBCursor cursor = servers.find();
            while(cursor.hasNext())
            {
                DBObject obj = cursor.next();
                if(((Long) obj.get("created") + 6000000L) < System.currentTimeMillis())
                {
                    //Destroy the instance and remove the active server from the MongoDB database
                    VultrQueryModule.destroy((String) obj.get("subid"));
                    DBObject remove = new BasicDBObject();
                    remove.put("_id",obj.get("_id"));

                    //Remove the list from the server's version of the active servers.
                    servers.remove(remove);
                }
            }
            Thread.sleep(10);
        }
    }
}
