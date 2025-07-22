package nu.marginalia.api.feeds;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.mq.outbox.MqOutbox;
import nu.marginalia.mq.persistence.MqPersistence;
import nu.marginalia.service.client.GrpcChannelPoolFactory;
import nu.marginalia.service.client.GrpcSingleNodeChannelPool;
import nu.marginalia.service.discovery.property.ServiceKey;
import nu.marginalia.service.discovery.property.ServicePartition;
import nu.marginalia.service.module.ServiceConfiguration;

import javax.annotation.CheckReturnValue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

@Singleton
public class FeedsClient {
    private static final boolean useLoom = Boolean.getBoolean("system.experimentalUseLoom");
    private static final ExecutorService executorService = useLoom ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newCachedThreadPool();

    private final GrpcSingleNodeChannelPool<FeedApiGrpc.FeedApiBlockingStub> channelPool;
    private final MqOutbox updateFeedsOutbox;

    @Inject
    public FeedsClient(GrpcChannelPoolFactory factory,
                       MqPersistence mqPersistence,
                       ServiceConfiguration serviceConfiguration) {

        // The client is only interested in the primary node
        var key = ServiceKey.forGrpcApi(FeedApiGrpc.class, ServicePartition.any());

        this.channelPool = factory.createSingle(key, FeedApiGrpc::newBlockingStub);
        this.updateFeedsOutbox = new MqOutbox(mqPersistence,
                "update-rss-feeds", 0,
                serviceConfiguration.serviceName(), serviceConfiguration.node(),
                UUID.randomUUID());
    }

    public CompletableFuture<RpcFeed> getFeed(int domainId) {
        try {
            return channelPool.call(FeedApiGrpc.FeedApiBlockingStub::getFeed)
                    .async(executorService)
                    .run(RpcDomainId.newBuilder().setDomainId(domainId).build());
        }
        catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public void getUpdatedDomains(Instant since, BiConsumer<String, List<String>> consumer) throws ExecutionException, InterruptedException {
        channelPool.call(FeedApiGrpc.FeedApiBlockingStub::getUpdatedLinks)
                .run(RpcUpdatedLinksRequest.newBuilder().setSinceEpochMillis(since.toEpochMilli()).build())
                .forEachRemaining(rsp -> consumer.accept(rsp.getDomain(), new ArrayList<>(rsp.getUrlList())));
    }

    public boolean waitReady(Duration duration) throws InterruptedException {
        return channelPool.awaitChannel(duration);
    }


    /** Get the hash of the feed data, for identifying when the data has been updated */
    public String getFeedDataHash() {
        return channelPool.call(FeedApiGrpc.FeedApiBlockingStub::getFeedDataHash)
                .run(Empty.getDefaultInstance())
                .getHash();
    }

    /** Update the feeds, return a message ID for the update */
    @CheckReturnValue
    public long updateFeeds(RpcFeedUpdateMode mode) throws Exception {
        // Create a message for the {@link MqLongRunningTask} paradigm to use for tracking the task
        long msgId = updateFeedsOutbox.sendAsync("updateFeeds", "");

        channelPool.call(FeedApiGrpc.FeedApiBlockingStub::updateFeeds)
                .run(RpcUpdateRequest.newBuilder()
                        .setMode(mode)
                        .setMsgId(msgId)
                        .build()
                );

        return msgId;
    }

}
