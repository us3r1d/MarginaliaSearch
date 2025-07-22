package nu.marginalia.assistant;

import com.google.gson.Gson;
import com.google.inject.Inject;
import io.jooby.Context;
import io.jooby.Jooby;
import nu.marginalia.assistant.suggest.Suggestions;
import nu.marginalia.domsample.DomSampleGrpcService;
import nu.marginalia.domsample.DomSampleService;
import nu.marginalia.functions.domains.DomainInfoGrpcService;
import nu.marginalia.functions.math.MathGrpcService;
import nu.marginalia.livecapture.LiveCaptureGrpcService;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.rss.svc.FeedsGrpcService;
import nu.marginalia.service.discovery.property.ServicePartition;
import nu.marginalia.service.server.BaseServiceParams;
import nu.marginalia.service.server.JoobyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AssistantService extends JoobyService {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Gson gson = GsonFactory.get();
    private final ScreenshotService screenshotService;
    private final Suggestions suggestions;

    @Inject
    public AssistantService(BaseServiceParams params,
                            ScreenshotService screenshotService,
                            DomainInfoGrpcService domainInfoGrpcService,
                            LiveCaptureGrpcService liveCaptureGrpcService,
                            DomSampleService domSampleService,
                            DomSampleGrpcService domSampleGrpcService,
                            FeedsGrpcService feedsGrpcService,
                            MathGrpcService mathGrpcService,
                            Suggestions suggestions)
            throws Exception
    {
        super(params, ServicePartition.any(),
                List.of(domainInfoGrpcService,
                        mathGrpcService,
                        liveCaptureGrpcService,
                        feedsGrpcService,
                        domSampleGrpcService
                        ),
                List.of());

        this.screenshotService = screenshotService;
        this.suggestions = suggestions;

        domSampleService.start();
    }

    public void startJooby(Jooby jooby) {
        super.startJooby(jooby);

        jooby.get("/suggest/", this::getSuggestions);
        jooby.get("/screenshot/{id}", screenshotService::serveScreenshotRequest);
    }

    private String getSuggestions(Context context) {
        context.setResponseType("application/json");
        var param = context.query("partial");
        if (param.isMissing()) {
            logger.warn("Bad parameter, partial is null");
            context.setResponseCode(500);
            return "{}";
        }
        return gson.toJson(suggestions.getSuggestions(10, param.value()));
    }

}
