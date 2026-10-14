package local.tv.home;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
public final class NetworkTest {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    interface Request{void run() throws Exception;}
    static void rejected(Request request,String name) throws Exception{boolean failed=false;try{request.run();}catch(Exception e){failed=true;}check(failed,name);}
    public static void main(String[] args) throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String root="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/ok",exchange->{byte[] b="playlist".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/ok");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/loop",exchange->{exchange.getResponseHeaders().add("Location","/loop");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/error",exchange->{exchange.sendResponseHeaders(401,-1);exchange.close();});
        server.createContext("/auth",exchange->{String header=exchange.getRequestHeaders().getFirst("Authorization");byte[] b=(header==null?"absent":"present").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});
        server.start();
        try{
            check(Network.text(root+"/ok",null,true).equals("playlist"),"Successful body");
            check(Network.text(root+"/redirect",null,true).equals("playlist"),"Relative redirect");
            check(Network.text(root+"/auth","fake-key",true).equals("absent"),"No bearer over HTTP");
            rejected(()->Network.bytes(root+"/ok",null,3,true),"Response size bound");
            rejected(()->Network.text(root+"/loop",null,true),"Redirect loop bound");
            rejected(()->Network.text(root+"/error",null,true),"HTTP error");
            rejected(()->Network.text(root+"/ok",null,false),"HTTP requires opt-in");
            System.out.println(checks+" network checks passed");
        }finally{server.stop(0);}
    }
}
