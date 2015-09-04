package net.voksul.vservers_websocket;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;

/**
 * Created by Chris on 5/27/2015.
 */
public class VultrQueryModule {
    //This class centralizes all of the code related to contacting vultr's servers to query and create new servers, along with shutting them down
    String location;
    HashMap<String, String> dcids = new HashMap<String, String>();
    public static final String api_key = "Vultr-Api-Key-Here";
    public final String SNAPSHOTID = "Snapshot-Id-Here"; //This is the ID from vultr of the snapshot that stores the server image containing the pre-configured TF2 server.

    public VultrQueryModule(String location) {
        //This constructor is only used when creating a server - the querying and deletion is done by the static methods
        this.location = location;

        //Establish the list of locations that can have servers created and their related DC IDs
        dcids.put("New Jersey", "1");
        dcids.put("Atlanta", "6");
        dcids.put("Chicago", "2");
        dcids.put("Dallas", "3");
        dcids.put("LosAngeles", "5");
        dcids.put("Miami", "39");
        dcids.put("Seattle", "4");
        dcids.put("SiliconValley", "12");
        dcids.put("Tokyo", "25");
        dcids.put("London", "8");
        dcids.put("Frankfurt", "9");
        dcids.put("Australia", "19");
        dcids.put("Amsterdam", "7");
        dcids.put("Paris", "24");
    }

    public JSONObject run() {
        JSONParser parser = new JSONParser();
        boolean locExists = false;
        String dcid = "-1";

        //Check to see if the chosen location exists
        if (dcids.get(location) != null) {
            locExists = true;
            dcid = dcids.get(location);
        }

        //If the location does exist, query it to create the server.
        if (locExists) {
            try {
                HttpURLConnection conn2 = (HttpURLConnection) new URL("https://api.vultr.com/v1/server/create?api_key=" + api_key).openConnection();
                conn2.setDoOutput(true);
                conn2.setRequestMethod("POST");

                //This specifies the DCID and the other information
                String data = "DCID=" + dcid + "&VPSPLANID=29&SNAPSHOTID=" + SNAPSHOTID + "&OSID=164";
                conn2.getOutputStream().write(data.getBytes());
                conn2.connect();

                //Parse the response and act based on it.
                JSONObject resp = (JSONObject) parser.parse(new InputStreamReader(conn2.getInputStream()));
                if (resp.get("SUBID") != null) {
                    //Created the server, return a json object with the id of the created vps
                    JSONObject returned = new JSONObject();
                    returned.put("vps_id", resp.get("SUBID").toString());
                    return returned;
                } else {
                    //Did not create the server, but vultr's error messages don't specify why usually.
                    JSONObject returned = new JSONObject();
                    returned.put("error", "Cannot create server for unspecified reason");
                    return returned;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParseException e) {
                e.printStackTrace();
            }
        } else {
            //Return an error if the location/DC does not exist
            JSONObject returned = new JSONObject();
            returned.put("error", "Location does not exist");
            return returned;
        }
        return null;
    }


    //This method simply queries the Vultr API and returns the data that it grabs from the API. It must be supplied with a SUBID/VPSID (Both are the same) in order to work
    public static JSONObject getData(String subid) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.vultr.com/v1/server/list?api_key=" + api_key + "&SUBID=" + subid).openConnection();
            conn.connect();
            JSONParser parser = new JSONParser();
            JSONObject obj = (JSONObject) parser.parse(new InputStreamReader(conn.getInputStream()));
            return obj;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return null;
    }

    //This method allows for the destruction of a server when it's time runs past or for other reasons. It requires the same argument as the method above.
    //This method could use a way to fallback if it can't delete the server to keep trying.
    public static JSONObject destroy(String subid) throws IOException {
        HttpURLConnection conn2 = (HttpURLConnection) new URL("https://api.vultr.com/v1/server/destroy?api_key=" + api_key).openConnection();
        conn2.setDoOutput(true);
        conn2.setRequestMethod("POST");
        String data = "SUBID=" + subid;
        conn2.getOutputStream().write(data.getBytes());
        conn2.connect();
        if (conn2.getResponseCode() == 200) {
            return null;
        }
        return null;
    }
}
