package client;

/**
 * Created by flbaue on 08.11.14.
 */
public class ClientStarter {
    public static void main(String[] args) {
        Account account = new Account("pop.gmx.net",995,"flo.bauer@gmx.net","olla85FLOW");
        Pop3Client pop3Client = new Pop3Client(account);
        pop3Client.connect();
        pop3Client.authorize();
    }
}