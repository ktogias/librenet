/**
 * @version 0.1
 * @author Konstantinos Togias <info@ktogias.gr>
 */
package plugins.Indynet;

import freenet.client.FetchException;
import freenet.client.FetchResult;
import freenet.clients.fcp.FCPPluginConnection;
import freenet.clients.fcp.FCPPluginMessage;
import freenet.clients.http.ToadletContainer;
import freenet.keys.FreenetURI;
import freenet.node.RequestStarter;
import freenet.pluginmanager.*;
import freenet.pluginmanager.FredPluginFCPMessageHandler.ServerSideFCPMessageHandler;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.api.RandomAccessBucket;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

/**
 * The plugin class
 *
 * @author Konstantinos Togias <info@ktogias.gr>
 */
public class Indynet implements FredPlugin, FredPluginThreadless, ServerSideFCPMessageHandler {

    private PluginRespirator pr; //The PluginRespirator object provided when runPlugin method is called.
    private final static String BASEPATH = "/indy:/"; //The base path under which the pugin is accessed. 
    private final static String RESOLV_FILE = "indynet.resolv.json";
    private final static int USERS_CACHE_SIZE = 50;
    private Map<String, User> usersCache;
    private IndynetCrypto crypto;
    
    /**
     * Dummy implementation of terminate method. 
     */
    @Override
    public void terminate() {
        usersCache.clear();
    }

    /**
     * Implementation of runPlugin method. This method runs when the plugin is
     * enabled.
     *
     * @param pr PluginRespirator : The PluginRespirator object
     */
    @Override
    public void runPlugin(PluginRespirator pr) {
        try {
            this.pr = pr;
            this.usersCache = Util.createLRUMap(USERS_CACHE_SIZE);
            this.crypto = new IndynetCrypto();
            ToadletContainer tc = pr.getToadletContainer(); //Get the container
            IndynetToadlet rt = new IndynetToadlet(BASEPATH, RESOLV_FILE, pr.getHLSimpleClient(), pr.getNode(), pr.getToadletContainer()); //Create the Toadlet that handles the HTTP requests
            tc.register(rt, null, rt.path(), true, false); //Resgister the Toadlet to the container
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(Indynet.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public FCPPluginMessage handlePluginFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm) {
        String action = fcppm.params.get("action");
        if (action.equalsIgnoreCase("resolver.register")){
            return handleResolverRegisterFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("resolver.resolve")){
            return handleResolverResolveFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("userauth.getUsernameHash")){
            return handleUserAuthGetUsernameHashFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("userauth.createAuthObject")){
            return handleUserAuthCreateAuthObjectFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("userauth.authenticate")){
            return handleUserAuthAuthenticateFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("insertData")){
            return handleInsertDataFCPMessage(fcppc, fcppm);
        }
        else if (action.equalsIgnoreCase("fetchData")){
            return handleFetchDataFCPMessage(fcppc, fcppm);
        }
        else {
            return FCPPluginMessage.constructErrorReply(fcppm, "NOT_SUPPORTED", "Indynet: Action not supported.");
        }
    }
    
    private FCPPluginMessage handleInsertDataFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        String insertKey = fcppm.params.get("insertKey");
        String filename = fcppm.params.get("filename");
        String contentType  = fcppm.params.get("contentType"); 
        int version = fcppm.params.getInt("version", -1);
        Bucket bucket = fcppm.data;
        boolean persistent = fcppm.params.getBoolean("persistent", false);
        boolean realtime = fcppm.params.getBoolean("realtime", false);
        short priorityClass = fcppm.params.getShort("priorityClass", RequestStarter.INTERACTIVE_PRIORITY_CLASS);
        try {
            FreenetURI insertURI = Util.BuildInsertURI(insertKey, filename, version);
            SimpleFieldSet params = new SimpleFieldSet(false);
            FreenetURI insertedURI = Util.insertData((RandomAccessBucket) bucket, insertURI, contentType, pr.getHLSimpleClient(), pr.getNode(), priorityClass, persistent, realtime, fcppc, fcppm);
            params.putSingle("insertedURI", insertedURI.toString());
            return Util.constructSuccessReplyMessage(fcppm, "InsertData", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "InsertData", "INSERT_FAILURE", "Data insert failed!", ex);
        } 
    }
    
    private FCPPluginMessage handleFetchDataFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        String url = fcppm.params.get("url");
        boolean persistent = fcppm.params.getBoolean("persistent", false);
        boolean realtime = fcppm.params.getBoolean("realtime", false);
        short priorityClass = fcppm.params.getShort("priorityClass", RequestStarter.INTERACTIVE_PRIORITY_CLASS);
        try {
            IndynetResolver resolver = new IndynetResolver(pr.getHLSimpleClient(), pr.getToadletContainer().getBucketFactory(), pr.getNode(), RESOLV_FILE, BASEPATH, fcppc, fcppm);
            URI uri = resolver.normalizeUri(new URI(url));
            SimpleFieldSet uriParts = resolver.decomposeUri(uri);
            String requestKey = uriParts.get("key");
            String requestPath = uriParts.get("path");
            String requestQuery = uriParts.get("query");
            if (!resolver.isFreenetKey(requestKey)){
                SimpleFieldSet resolveStartResult = new SimpleFieldSet(false);
                resolveStartResult.putSingle("phase","resolve");
                resolveStartResult.putSingle("phaseStatus","tostart");
                resolveStartResult.putSingle("requestKey", requestKey);
                FCPPluginMessage resolveStartReplyMessage = Util.constructReplyMessage(fcppm, "FetchData", "progress", resolveStartResult);
                fcppc.send(resolveStartReplyMessage);
                requestKey = resolver.resolve(requestKey);
                SimpleFieldSet resolveSuccessResult = new SimpleFieldSet(false);
                resolveSuccessResult.putSingle("phase","resolve");
                resolveSuccessResult.putSingle("phaseStatus","success");
                resolveSuccessResult.putSingle("requestKey", requestKey);
                FCPPluginMessage resolveSuccessReplyMessage = Util.constructReplyMessage(fcppm, "FetchData", "progress", resolveSuccessResult);
                fcppc.send(resolveSuccessReplyMessage);
            }
            String newUriStr = requestKey;
            if (requestPath != null){
                newUriStr += requestPath;
            }
            if (requestQuery != null){
                newUriStr += "?"+requestQuery;
            }
            FreenetURI furi = new FreenetURI(newUriStr);
            FetchResult result = Util.fetchData(furi, pr.getHLSimpleClient(), pr.getNode(), priorityClass, persistent, realtime, fcppc, fcppm);
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("fetchedURI", furi.toString());
            params.putSingle("mimeType", result.getMimeType());
            return Util.constructSuccessReplyMessage(fcppm, "FetchData", params, result.asBucket());
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "FetchData", "FETCH_FAILURE", "Data fetch failed!", ex);
        } 
    }
    
    private FCPPluginMessage handleResolverRegisterFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        String name = fcppm.params.get("name");
        String requestKey = fcppm.params.get("requestKey");
        boolean persistent = fcppm.params.getBoolean("persistent", false);
        boolean realtime = fcppm.params.getBoolean("realtime", false);
        short priorityClass = fcppm.params.getShort("priorityClass", RequestStarter.INTERACTIVE_PRIORITY_CLASS);
        try {
            IndynetResolver resolver = new IndynetResolver(pr.getHLSimpleClient(), pr.getToadletContainer().getBucketFactory(), pr.getNode(), RESOLV_FILE, BASEPATH, fcppc, fcppm);
            FreenetURI insertedURI = resolver.register(requestKey, name, priorityClass, persistent, realtime);
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("insertedURI", insertedURI.toString());
            return Util.constructSuccessReplyMessage(fcppm, "Resolver", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "Resolver", "REGISTER_FAILURE", "Register failed!", ex);
        } 
    }
    
    private FCPPluginMessage handleResolverResolveFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        String name = fcppm.params.get("name");
        boolean persistent = fcppm.params.getBoolean("persistent", false);
        boolean realtime = fcppm.params.getBoolean("realtime", false);
        short priorityClass = fcppm.params.getShort("priorityClass", RequestStarter.INTERACTIVE_PRIORITY_CLASS);
        try {
            IndynetResolver resolver = new IndynetResolver(pr.getHLSimpleClient(), pr.getToadletContainer().getBucketFactory(), pr.getNode(), RESOLV_FILE, BASEPATH, fcppc, fcppm);
            String requestKey = resolver.resolve(name, priorityClass, persistent, realtime);
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("requestKey", requestKey);
            return Util.constructSuccessReplyMessage(fcppm, "Resolver", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "Resolver", "RESOLVE_FAILURE", "Resolve failed!", ex);
        }
    }
    
    private FCPPluginMessage handleUserAuthGetUsernameHashFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        try {
            IndynetUserAuth auth = new IndynetUserAuth(crypto);
            String hash = auth.getUsernameHash(fcppm.params.get("username"));
            fcppm.params.removeValue("username");
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("hash", hash);
            return Util.constructSuccessReplyMessage(fcppm, "UserAuth", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "UserAuth", "AUTH_GET_USERNAME_HASH_FAILURE", "Get username hash failed!", ex);
        }
        
    }
    
    private FCPPluginMessage handleUserAuthCreateAuthObjectFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        try {
            IndynetUserAuth auth = new IndynetUserAuth(crypto);
            JSONObject authObject = auth.createAuthObject(fcppm.params.get("username"), fcppm.params.get("password"));
            fcppm.params.removeValue("username");
            fcppm.params.removeValue("password");
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("authObject", authObject.toJSONString());
            return Util.constructSuccessReplyMessage(fcppm, "UserAuth", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "UserAuth", "AUTH_CREATE_AUTH_OBJECT_FAILURE", "Construction of auth object failed!", ex);
        } 
    }
    
    private FCPPluginMessage handleUserAuthAuthenticateFCPMessage(FCPPluginConnection fcppc, FCPPluginMessage fcppm){
        try {
            JSONParser parser = new JSONParser();
            IndynetUserAuth auth = new IndynetUserAuth(crypto);
            User user = auth.authenticate(
                    (JSONObject) parser.parse(fcppm.params.get("authObject")), 
                    fcppm.params.get("username"), 
                    fcppm.params.get("password"));
            fcppm.params.removeValue("authObject");
            fcppm.params.removeValue("username");
            fcppm.params.removeValue("password");
            if (!usersCache.containsKey(user.getHash())){
                usersCache.put(user.getHash(), user);
            }
            SimpleFieldSet params = new SimpleFieldSet(false);
            params.putSingle("status", "success");
            params.putSingle("userHash", user.getHash());
            return Util.constructSuccessReplyMessage(fcppm, "UserAuth", params);
        } catch (Exception ex) {
            return Util.constructFailureReplyMessage(fcppm, "UserAuth", "AUTHENTICATE_FAILURE", "Authentication failed!", ex);
        } 
    }
}
