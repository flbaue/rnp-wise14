package flaue.pop3proxy.client;

import flaue.pop3proxy.client.requests.*;
import flaue.pop3proxy.client.responses.ErrResponse;
import flaue.pop3proxy.client.responses.OkResponse;
import flaue.pop3proxy.client.responses.Response;
import flaue.pop3proxy.client.responses.Responses;
import flaue.pop3proxy.common.Account;
import flaue.pop3proxy.common.Mail;
import flaue.pop3proxy.common.Pop3States;
import flaue.pop3proxy.mailstore.MailStore;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Created by flbaue on 08.11.14.
 */

public class Pop3Client implements AutoCloseable {

    private Socket connection;
    private Account account;
    private BufferedReader in;
    private BufferedWriter out;
    private Pop3States state;
    private MailStore mailStore;

    public Pop3Client(Account account, MailStore mailStore) {
        this.account = account;
        this.mailStore = mailStore;
        this.mailStore.addStore(account);
        state = Pop3States.DISCONNECTED;
    }

    public void fetchMails() throws IOException {
        connect();
        authorize();
        Set<MailInfo> mailInfos = list();
        List<Mail> mails = downloadMails(mailInfos);
        mails = storeMails(mails);
        printLog(mails);
        deleteMailsFromServer(mailInfos);
    }

    private void printLog(List<Mail> mails) {
        String out = "--------------------\n" +
                "Pop3Client Mail Download\n" +
                "Account: " + account;

        int count = 1;
        for (Mail mail : mails) {
            out += count + " " + mail.toString() + "\n";
            count += 1;
        }
        System.out.println(out);
    }

    private void deleteMailsFromServer(Set<MailInfo> mailInfos) {
        for (MailInfo mailInfo : mailInfos) {
            dele(mailInfo);
        }
    }

    private void connect() throws IOException {

        requireState(Pop3States.DISCONNECTED);

//        SSLSocketFactory ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
//        connection = ssf.createSocket(account.getServer(), account.getPort());
        connection = new Socket(account.getServer(), account.getPort());
        in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
        out = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"));

        Responses.requireOk(readResponse());

        state = Pop3States.AUTHORIZATION;
    }

    private void disconnect() {

        prohibitState(Pop3States.DISCONNECTED);

        //TODO make pretty
        try {
            in.close();
            out.close();
            connection.close();
        } catch (IOException e) {

        } finally {
            try {
                connection.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        state = Pop3States.DISCONNECTED;
    }

    private List<Mail> downloadMails(Set<MailInfo> mailInfos) {
        List<Mail> mails = new ArrayList<>(mailInfos.size());

        for (MailInfo mailInfo : mailInfos) {
            Mail mail = retr(mailInfo);
            mails.add(mail);
        }

        return mails;
    }

    private List<Mail> storeMails(List<Mail> mails) {
        List<Mail> storedMails = new LinkedList<>();
        for (Mail mail : mails) {
            Mail storedMail = mailStore.storeMail(account, mail);
            storedMails.add(storedMail);
        }
        return storedMails;
    }

    private void authorize() {
        requireState(Pop3States.AUTHORIZATION);

        username();
        password();

        state = Pop3States.TRANSACTION;
    }

    private void username() {
        sendRequestAndRequireOk(new UserRequest(account.getUsername()));
    }

    private void password() {
        sendRequestAndRequireOk(new PassRequest(account.getPassword()));
    }

    private Set<MailInfo> list() {
        requireState(Pop3States.TRANSACTION);

        sendRequest(new ListRequest());
        Response response = readMultiLineResponse();

        if (response.getCommand().equals(ErrResponse.COMMAND)) {
            throw new RuntimeException(response.getPayload());
        }

        Set<MailInfo> results = new HashSet<>();
        String[] lines = response.getPayload().split("\r\n");
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i].trim();
            String[] parts = line.split("\\s");
            results.add(new MailInfo(parts[0].trim(), parts[1].trim()));
        }

        return results;
    }

    private Mail retr(MailInfo mailInfo) {
        requireState(Pop3States.TRANSACTION);

        sendRequest(new RetrRequest(mailInfo.getIndex()));
        Response response = readMultiLineResponse();
        if (response.getCommand().equals(ErrResponse.COMMAND)) {
            throw new IllegalArgumentException(response.getPayload());
        }
        int endLine1 = response.getPayload().indexOf("\r\n");
        String body = response.getPayload().substring(endLine1 + 2);

        return new Mail(body);
    }

    private void dele(MailInfo mailInfo) {
        requireState(Pop3States.TRANSACTION);
        sendRequestAndRequireOk(new DeleRequest(mailInfo.getIndex()));
    }

    private void requireState(Pop3States state) {
        if (this.state != state) {
            throw new IllegalStateException("State required: " + state.name() + " actual: " + state.name());
        }
    }

    private void prohibitState(Pop3States state) {
        if (this.state == state) {
            throw new IllegalStateException("State prohibited: " + state.name());
        }
    }

    private void sendRequest(Request request) {
        try {
            System.out.println("Client OUT:" + request.toStringWithLineEnd());
            out.write(request.toStringWithLineEnd());
            out.flush();
        } catch (IOException e) {
            //TODO maybe handle by closing the flaue.pop3proxy.client?
            throw new RuntimeException("Cannot send request", e);
        }
    }

    private Response sendRequestAndRequireOk(Request request) {
        sendRequest(request);
        Response response = readResponse();
        Responses.requireOk(response);
        return response;
    }

    private Response readResponse() {
        try {
            String line = "";
            line = in.readLine();
            System.out.println("Client IN: " + line);
            return Responses.createResponse(line);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read response", e);
        }
    }

    private Response readMultiLineResponse() {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            String line = in.readLine();

            if (line.startsWith(ErrResponse.COMMAND)) {
                return new ErrResponse(line);
            }

            stringBuilder.append(line + "\r\n");
            while (!line.equals(".")) {
                line = in.readLine();
                stringBuilder.append(line + "\r\n");
            }
            String input = stringBuilder.toString();
            System.out.println("Client IN:" + input);
            return new OkResponse(input);

        } catch (IOException e) {
            throw new RuntimeException("Cannot read response", e);
        }
    }

    @Override
    public void close() throws Exception {
        disconnect();
    }
}
