package net.voksul.vservers_websocket;

import com.github.koraktor.steamcondenser.exceptions.SteamCondenserException;
import com.github.koraktor.steamcondenser.steam.servers.SourceServer;
import com.mongodb.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;
import java.util.concurrent.TimeoutException;

/**
 * Created by Chris on 5/27/2015.
 */
public class WebSocketServ extends WebSocketServer {
    HashMap<WebSocket,String> loggedIn = new HashMap<WebSocket,String>();
    MongoClient mongo;
    DB db;
    DBCollection servers;
    DBCollection users;

    final String serverIP = "127.0.0.1";

    final String mongoUser = "admin";
    final String mongoDatabase = "dbName";
    final String mongoPassword = "password-here";

    final String defaultRconPass = "default-rcon";
    public WebSocketServ(InetSocketAddress address) {
        super(address); //This creates a new websocket server at the given address
        mongo = new MongoClient(new ServerAddress("127.0.0.1"), Arrays.asList(MongoCredential.createCredential(mongoUser,mongoDatabase,mongoPassword.toCharArray())));
        mongo.setWriteConcern(WriteConcern.JOURNALED);
        //Get the necessary databases and collections and alert the user via console that the server has started.
        db = mongo.getDB("vservers");
        servers = db.getCollection("servers");

        users = db.getCollection("users");
        System.out.println("Started..");
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
        //Nothing is done here since most of the work is done in the message section
    }

    @Override
    public void onClose(WebSocket webSocket, int i, String s, boolean b) {
        //Nothing is done here either for the same reason as above.
    }

    @Override
    public void onMessage(WebSocket webSocket, String s) {
        System.out.println("Recieved message " + s + " from " + webSocket.getRemoteSocketAddress().getAddress().toString()); //Log messages to console
        //Messages are sent in a format such that they have a prefix, then a colon, then the data following it.
        if(s.startsWith("id"))
        {
            //Extract encrypted steam id
            String[] id = s.split(":",3);
            if(id.length == 3)
            {
                //Check the key along with their steamid to make sure it matches correctly - this is a security measure to prevent people from using steamids that don't belong to them.
                if(checkKey(id[1],id[2])) {
                    loggedIn.put(webSocket, id[1]); //Set them as logged in
                    BasicDBObject query = new BasicDBObject();
                    query.put("user", id[1]);

                    //If they don't exist, create a user and give them 25 remaining rentals.
                    if (users.find(query).size() == 0) {
                        BasicDBObject insert = new BasicDBObject();
                        insert.put("user", id[1]);
                        insert.put("rentsLeft", 25);
                        users.insert(insert);
                    }
                }else{
                    webSocket.send("error:Invalid session");
                }
            }else{
                webSocket.send("error:Invalid request.");
            }
        }else if(s.startsWith("create"))
        {
            //This is the message to create a server
            String[] location = s.split(":",2);
            System.out.println("Location: " + location[1]); //Print out the location being created to console
            if(location.length == 2) {
                //Attempt to create a server, after querying to see if one already exists for that key.
                if (loggedIn.get(webSocket) != null) { //Check to make sure they are logged in
                    BasicDBObject query = new BasicDBObject();
                    query.put("user", loggedIn.get(webSocket));
                    DBCursor cursor = servers.find(query);
                    //Check to make sure they don't already have a server
                    if (cursor.size() < 1) {
                        //They don't have a server, proceed.
                        BasicDBObject userQuery = new BasicDBObject();
                        userQuery.put("user",loggedIn.get(webSocket));
                        DBCursor userCursor = users.find(userQuery);
                        //Check to make sure their user actually exists. --- this should probably be done earlier?
                        if(userCursor.size() == 1) {
                            DBObject next = userCursor.next();
                            //Check to make sure they have rentals remaining.
                            if ((Integer) next.get("rentsLeft") > 0) {
                                JSONObject obj = new VultrQueryModule(location[1]).run();
                                //Create the server via the vultr query module
                                if (!obj.containsKey("error")) {
                                    //If the response isn't an error, act on the response.
                                    BasicDBObject insert = new BasicDBObject();

                                    //Insert the response data into the database
                                    JSONObject serverData = VultrQueryModule.getData(obj.get("vps_id").toString());
                                    //insert.put("ip", serverData.get("main_ip")); -- Originally the ip was inserted, but vultr changed it so that vpses aren't intially assigned an IP, making this fundamentally useless.
                                    insert.put("created", System.currentTimeMillis());
                                    insert.put("user", loggedIn.get(webSocket));
                                    insert.put("rcon",defaultRconPass);
                                    insert.put("subid", obj.get("vps_id"));
                                    servers.insert(insert);
                                    //They have one now. Inform the clientside
                                    webSocket.send("created:" + obj.get("vps_id"));

                                    //Use a mongodb query to reduce the number of rentals they have left by one.
                                    BasicDBObject updatedDoc = new BasicDBObject();
                                    updatedDoc.put("$set", new BasicDBObject().append("rentsLeft", ((Integer) next.get("rentsLeft")) - 1));
                                    BasicDBObject search = new BasicDBObject().append("user",loggedIn.get(webSocket));
                                    users.update(search,updatedDoc);
                                } else {
                                    webSocket.send("error:" + obj.get("error"));
                                }
                            }else{
                                webSocket.send("error:You have no rents left for now.");
                            }
                        }else{
                            webSocket.send("error:More than one user by your steam id.");
                        }
                    } else {
                        webSocket.send("error:Cannot create more than 1 server per user.");
                    }
                } else {
                    //They aren't logged in, send error.
                    webSocket.send("error:Not logged in.");
                }
            }else{
                webSocket.send("error:Invalid request.");
            }
        }else if(s.startsWith("query"))
        {
            //Client wants to query to see if they have a server, and if so what the data for it is
            String[] sub = s.split(":",2);
            if(sub.length == 2)
            {
                //Validate the request
                JSONObject serverData = VultrQueryModule.getData(sub[1]);
                //TODO: Check to make sure they have a server - however this works without the check for the timebeing.
                BasicDBObject query = new BasicDBObject();
                query.put("user",loggedIn.get(webSocket));
                DBCursor cursor = servers.find(query);
                //Check the server data for the server to see if it's been assigned an ip yet due to the changes vultr made.
                if(!serverData.get("main_ip").equals("0")) {
                    if (cursor.size() > 0) {
                        try {
                            //If it has been created, use the Steam-Condenser library to query the data - the servers will always be running on the default port for TF2
                            SourceServer server = new SourceServer((String) serverData.get("main_ip"), 27015);
                            server.getPing();

                            //Get them their rcon password
                            DBObject obj = cursor.next();
                            String rcon = (String) obj.get("rcon");

                            //This is a messy way to format the text, but it works. This shows them when the server will expire. The single quotes allow for text to be not parsed by the date format.
                            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy  'at' hh'&#58;'mm'&#58;'ss a z");
                            sdf.setTimeZone(TimeZone.getTimeZone("GMT-4"));
                            if(!rcon.equals(defaultRconPass)) {
                                //Show them the result with the password as it isn't the default
                                webSocket.send("status:Ready|" + serverData.get("main_ip") + "|"+obj.get("rcon")+"|" + sdf.format(new Date(((Long)obj.get("created"))+6000000L)));
                            }else{
                                //Show them the result without the password as they haven't requested one yet.
                                webSocket.send("status:Ready|" + serverData.get("main_ip") + "|None|" + sdf.format(new Date(((Long)obj.get("created"))+6000000L)));
                            }
                            return;
                        } catch (SteamCondenserException e) {
                            e.printStackTrace();
                        } catch (TimeoutException e) {
                            e.printStackTrace();
                        }
                        webSocket.send("status:Not Ready");
                    } else {
                        webSocket.send("error:no_server");
                    }
                }else{
                    webSocket.send("status:Server not assigned an IP yet.");
                }
            }else{
                webSocket.send("error:Invalid request.");
            }
        }else if(s.startsWith("destroy"))
        {
            //User wants to destroy their server, for whatever reason.
            if(loggedIn.get(webSocket) != null) {
                //Check to make sure they are logged in.
                BasicDBObject query = new BasicDBObject();
                query.put("user", loggedIn.get(webSocket));
                DBCursor cursor = servers.find(query);
                while (cursor.hasNext()) {
                    //Loop through and delete each of their servers.
                    DBObject obj = cursor.next();
                    try {
                        VultrQueryModule.destroy((String) obj.get("subid"));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }else if(s.startsWith("rcon"))
        {
            //Client requests an rcon password - generate one
            if(loggedIn.get(webSocket) != null) {
                //Check to make sure that they are logged in.

                BasicDBObject query = new BasicDBObject();
                query.put("user", loggedIn.get(webSocket));
                DBCursor cursor = servers.find(query);
                DBObject obj = cursor.next();
                JSONObject serverData = VultrQueryModule.getData((String) obj.get("subid"));

                //Check to make sure they have a server with an IP that isn't 0.
                if(!serverData.get("main_ip").equals("0"))
                {
                    SourceServer server = null;
                    try {
                        server = new SourceServer((String) serverData.get("main_ip"), 27015);
                        server.getPing();

                        //Connect with the pre-defined rcon password.
                        server.rconAuth((String) obj.get("rcon"));

                        //Generate an rcon password and take a subsection of it
                        String password = getRconPass().substring(0, 5);
                        server.rconExec("rcon_password " + password);

                        //Update the RCON password in the database
                        BasicDBObject updatedDoc = new BasicDBObject();
                        updatedDoc.put("$set",new BasicDBObject("rcon",password));
                        BasicDBObject search = new BasicDBObject().append("user",loggedIn.get(webSocket));
                        servers.update(search,updatedDoc);
                        webSocket.send("rconc:"+password);
                    } catch (SteamCondenserException e) {
                        e.printStackTrace();
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                    }
                }else{
                    webSocket.send("error:No ip assigned to this server yet.");
                }
            }
        }
    }

    @Override
    public void onError(WebSocket webSocket, Exception e) {
        //This method also does nothing.
    }

    public String getRconPass() {
        try {
            //Create an rcon password for them to use using MD5.
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] data = (md5.digest(String.valueOf(System.currentTimeMillis()).getBytes()));
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < data.length; i++) {
                sb.append(Integer.toString((data[i] & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    public boolean checkKey(String key, String steam_id)
    {
        MessageDigest md5 = null;
        final String validationKey = "validation-key";
        try {
            md5 = MessageDigest.getInstance("MD5");

            //Get a version of the correct key based on their steamID
            byte[] data = (md5.digest((steam_id + "." + validationKey).getBytes()));
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < data.length; i++) {
                sb.append(Integer.toString((data[i] & 0xff) + 0x100, 16).substring(1));
            }

            //Check to see if the keys match
            if(sb.toString().equals(key))
            {
                return true;
            }else{
                return false;
            }
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return false;
    }
}
