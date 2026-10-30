package com.sky.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** 管理端通知连接，兼容原 /ws/{sid} 地址。 */
@Component
@Slf4j
@ServerEndpoint("/ws/{sid}")
public class WebSocketServer {
    // 使用真实会话ID，避免同一 sid 重连后，旧连接关闭时误删新连接。
    private static final ConcurrentMap<String, Client> sessions = new ConcurrentHashMap<>();

    private static class Client {
        final Session session;
        final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();
        final AtomicBoolean sending = new AtomicBoolean();
        Client(Session session) { this.session = session; }
    }

    @OnOpen
    public void onOpen(Session session) {
        session.setMaxIdleTimeout(90_000);
        session.getAsyncRemote().setSendTimeout(5_000);
        sessions.put(session.getId(), new Client(session));
        log.info("管理端通知连接已建立，在线连接数={}", sessions.size());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        if ("ping".equals(message)) {
            Client client = sessions.get(session.getId());
            if (client != null && client.session == session) send(client, "pong");
        }
    }

    @OnClose
    public void onClose(Session session) {
        sessions.computeIfPresent(session.getId(), (id, client) -> client.session == session ? null : client);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            Client client = sessions.get(session.getId());
            if (client != null && client.session == session) discard(client);
        }
        log.warn("管理端通知连接异常，类型={}", error.getClass().getSimpleName());
    }

    public void sendToAllClient(String message) {
        sessions.values().forEach(client -> send(client, message));
    }

    private void send(Client client, String message) {
        if (!client.session.isOpen()) {
            sessions.remove(client.session.getId(), client);
            return;
        }
        // 慢连接单独清理，避免阻塞支付线程或无限积压通知。
        if (client.messages.size() >= 100) { discard(client); return; }
        client.messages.add(message);
        if (client.sending.compareAndSet(false, true)) writeNext(client);
    }

    private void writeNext(Client client) {
        String message = client.messages.poll();
        if (message == null) {
            client.sending.set(false);
            // 处理刚清空队列时另一个线程入队的竞争。
            if (!client.messages.isEmpty() && client.sending.compareAndSet(false, true)) writeNext(client);
            return;
        }
        try {
            // 必须等上一条消息的异步发送完成，再开始下一条。
            client.session.getAsyncRemote().sendText(message, result -> {
                if (result.isOK()) writeNext(client);
                else discard(client);
            });
        } catch (RuntimeException ex) {
            discard(client);
        }
    }

    private void discard(Client client) {
        sessions.remove(client.session.getId(), client);
        client.messages.clear();
        try {
            if (client.session.isOpen()) {
                client.session.close();
            }
        } catch (IOException ignored) {
            // 一个失效连接不能影响其他管理端连接或订单提交。
        }
    }
}
