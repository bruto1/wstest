package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketMessage.Type;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

@SpringBootTest(
    properties = {
        "spring.main.web-application-type=reactive",
        "spring.resources.add-mappings=false"
    },
    webEnvironment = WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration
@SpringBootConfiguration
class WSTest {
    static final DataBufferFactory dataBufferFactory = new DefaultDataBufferFactory();

    @LocalServerPort
    private int port;

    @Configuration
    private static class TestConfig {
        public TestConfig() {
        }

        @Bean
        public ReactiveWebServerFactory reactiveWebServerFactory() {
            return new NettyReactiveWebServerFactory();
        }

        @Bean
        public HandlerMapping webSocketMapping() {
            SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
            mapping.setUrlMap(Map.of("/foo", new WShandler()));
            return mapping;
        }

        @Bean
        public WebSocketHandlerAdapter handlerAdapter(/*HandshakeWebSocketService webSocketService*/) {
            return new WebSocketHandlerAdapter();
        }
    }

    public static Flux<WebSocketMessage> requestChannel(
        WebSocketClient wsClient, URI uri, Flux<WebSocketMessage> outbound) {

        CompletableFuture<Flux<WebSocketMessage>> recvFuture = new CompletableFuture<>();
        CompletableFuture<Integer> consumerDoneCallback = new CompletableFuture<>();

        Mono<Void> executeMono = wsClient.execute(uri,
            wss -> {
                recvFuture.complete(wss.receive());
                return wss.send(outbound)
                          .and(Mono.fromFuture(consumerDoneCallback));
            }).log("requestChannel.execute " + uri);

        return Mono.fromFuture(recvFuture)
                   .flatMapMany(recv -> recv.doOnComplete(() -> consumerDoneCallback.complete(1)))
                   .mergeWith(executeMono.cast(WebSocketMessage.class));
    }

    @Test
    void testComplete() {
        var outbound = Flux.just(1)
                           .map(String::valueOf)
                           .log("client send")
                           .map(str -> new WebSocketMessage(Type.TEXT,
                               dataBufferFactory.wrap(str.getBytes(StandardCharsets.UTF_8))));

        var recv = requestChannel(new ReactorNettyWebSocketClient(),
            URI.create("ws://localhost:" + port + "/foo"), outbound)
            .map(WebSocketMessage::getPayloadAsText)
            .log("client recv");

        StepVerifier.create(recv)
                    .expectNext("f(1)")
                    .verifyComplete();
    }


    private static class WShandler implements WebSocketHandler {
        @Override
        public Mono<Void> handle(WebSocketSession session) {
            return session.send(
                session.receive()
                       .map(WebSocketMessage::getPayloadAsText)
                       .log("server receive")
                       .map(str -> "f(" + str + ")")
                       .log("server send")
                       .map(str -> new WebSocketMessage(Type.TEXT,
                           dataBufferFactory.wrap(str.getBytes(StandardCharsets.UTF_8))))
            );
        }
    }
}
