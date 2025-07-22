package nu.marginalia.api.math;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import nu.marginalia.api.math.MathProtobufCodec.DictionaryLookup;
import nu.marginalia.api.math.MathProtobufCodec.EvalMath;
import nu.marginalia.api.math.MathProtobufCodec.SpellCheck;
import nu.marginalia.api.math.MathProtobufCodec.UnitConversion;
import nu.marginalia.api.math.model.DictionaryResponse;
import nu.marginalia.service.client.GrpcChannelPoolFactory;
import nu.marginalia.service.client.GrpcSingleNodeChannelPool;
import nu.marginalia.service.discovery.property.ServiceKey;
import nu.marginalia.service.discovery.property.ServicePartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


@Singleton
public class MathClient {
    private static final Logger logger = LoggerFactory.getLogger(MathClient.class);

    private final GrpcSingleNodeChannelPool<MathApiGrpc.MathApiBlockingStub> channelPool;

    private static final boolean useLoom = Boolean.getBoolean("system.experimentalUseLoom");
    private static final ExecutorService executor = useLoom ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newWorkStealingPool(8);

    @Inject
    public MathClient(GrpcChannelPoolFactory factory) {
        this.channelPool = factory.createSingle(
                ServiceKey.forGrpcApi(MathApiGrpc.class, ServicePartition.any()),
                MathApiGrpc::newBlockingStub);

    }

    public Future<DictionaryResponse> dictionaryLookup(String word) {
        return channelPool.call(MathApiGrpc.MathApiBlockingStub::dictionaryLookup)
                .async(executor)
                .run(DictionaryLookup.createRequest(word))
                .thenApply(DictionaryLookup::convertResponse);
    }

    @SuppressWarnings("unchecked")
    public Future<List<String>> spellCheck(String word) {
        return channelPool.call(MathApiGrpc.MathApiBlockingStub::spellCheck)
                .async(executor)
                .run(SpellCheck.createRequest(word))
                .thenApply(SpellCheck::convertResponse);
    }

    // This looks a bit different because we need to spell check multiple words, and we want to do it in parallel
    public Future<Map<String, List<String>>> spellCheck(List<String> words) throws InterruptedException {
        List<RpcSpellCheckRequest> requests = words.stream().map(SpellCheck::createRequest).toList();

        return channelPool.call(MathApiGrpc.MathApiBlockingStub::spellCheck)
                .async(executor)
                .runFor(requests)
                .thenApply(rsp -> SpellCheck.convertResponses(words, rsp));
    }

    public Future<String> unitConversion(String value, String from, String to) {
        return channelPool.call(MathApiGrpc.MathApiBlockingStub::unitConversion)
                .async(executor)
                .run(UnitConversion.createRequest(from, to, value))
                .thenApply(UnitConversion::convertResponse);
    }

    public Future<String> evalMath(String expression) {
        return channelPool.call(MathApiGrpc.MathApiBlockingStub::evalMath)
                .async(executor)
                .run(EvalMath.createRequest(expression))
                .thenApply(EvalMath::convertResponse);
    }

    public boolean isAccepting() {
        return channelPool.hasChannel();
    }
}
