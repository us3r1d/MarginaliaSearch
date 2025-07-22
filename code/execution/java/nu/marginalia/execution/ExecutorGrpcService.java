package nu.marginalia.execution;

import com.google.inject.Inject;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import nu.marginalia.WmsaHome;
import nu.marginalia.actor.ActorApi;
import nu.marginalia.actor.ExecutorActor;
import nu.marginalia.actor.ExecutorActorControlService;
import nu.marginalia.actor.state.ActorStateInstance;
import nu.marginalia.actor.task.DownloadSampleActor;
import nu.marginalia.actor.task.RestoreBackupActor;
import nu.marginalia.actor.task.TriggerAdjacencyCalculationActor;
import nu.marginalia.actor.task.UpdateNsfwFiltersActor;
import nu.marginalia.functions.execution.api.*;
import nu.marginalia.service.module.ServiceConfiguration;
import nu.marginalia.service.server.DiscoverableService;
import nu.marginalia.storage.FileStorageService;
import nu.marginalia.storage.model.FileStorageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

public class ExecutorGrpcService
        extends ExecutorApiGrpc.ExecutorApiImplBase
        implements DiscoverableService
{
    private final ActorApi actorApi;
    private final FileStorageService fileStorageService;
    private final ServiceConfiguration serviceConfiguration;
    private final ExecutorActorControlService actorControlService;

    private static final Logger logger = LoggerFactory.getLogger(ExecutorGrpcService.class);

    @Inject
    public ExecutorGrpcService(ActorApi actorApi,
                               FileStorageService fileStorageService,
                               ServiceConfiguration serviceConfiguration,
                               ExecutorActorControlService actorControlService)
    {
        this.actorApi = actorApi;
        this.fileStorageService = fileStorageService;
        this.serviceConfiguration = serviceConfiguration;
        this.actorControlService = actorControlService;
    }

    @Override
    public void startFsm(RpcFsmName request, StreamObserver<Empty> responseObserver) {
        try {
            actorApi.startActor(request);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void stopFsm(RpcFsmName request, StreamObserver<Empty> responseObserver) {
        try {
            actorApi.stopActor(request);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void stopProcess(RpcProcessId request, StreamObserver<Empty> responseObserver) {
        try {
            actorApi.stopProcess(request);
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void calculateAdjacencies(Empty request, StreamObserver<Empty> responseObserver) {
        try {
            actorControlService.startFrom(ExecutorActor.ADJACENCY_CALCULATION,
                    new TriggerAdjacencyCalculationActor.Run());

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void downloadSampleData(RpcDownloadSampleData request, StreamObserver<Empty> responseObserver) {
        try {
            String sampleSet = request.getSampleSet();

            actorControlService.startFrom(ExecutorActor.DOWNLOAD_SAMPLE,
                    new DownloadSampleActor.Run(sampleSet));

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void restoreBackup(RpcFileStorageId request, StreamObserver<Empty> responseObserver) {
        try {
            var fid = FileStorageId.of(request.getFileStorageId());

            actorControlService.startFrom(ExecutorActor.RESTORE_BACKUP,
                    new RestoreBackupActor.Restore(fid));

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void getActorStates(Empty request, StreamObserver<RpcActorRunStates> responseObserver) {
        var items = actorControlService.getActorStates().entrySet().stream().map(e -> {
                    final var stateGraph = actorControlService.getActorDefinition(e.getKey());

                    final ActorStateInstance state = e.getValue();
                    final String actorDescription = stateGraph.describe();

                    final String machineName = e.getKey().name();
                    final String stateName = state.name();

                    final String stateDescription = "";

                    final boolean terminal = state.isFinal();
                    final boolean canStart = actorControlService.isDirectlyInitializable(e.getKey()) && terminal;

                    return RpcActorRunState
                            .newBuilder()
                            .setActorName(machineName)
                            .setState(stateName)
                            .setActorDescription(actorDescription)
                            .setStateDescription(stateDescription)
                            .setTerminal(terminal)
                            .setCanStart(canStart)
                            .build();

                })
                .filter(s -> !s.getTerminal() || s.getCanStart())
                .sorted(Comparator.comparing(RpcActorRunState::getActorName))
                .toList();

        responseObserver.onNext(RpcActorRunStates.newBuilder()
                .setNode(serviceConfiguration.node())
                .addAllActorRunStates(items)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void listSideloadDir(Empty request, StreamObserver<RpcUploadDirContents> responseObserver) {
        try {
            Path uploadDir = WmsaHome.getUploadDir();

            try (var items = Files.list(uploadDir).sorted(
                    Comparator.comparing((Path d) -> Files.isDirectory(d)).reversed()
                            .thenComparing(path -> path.getFileName().toString())
            )) {
                var builder = RpcUploadDirContents.newBuilder().setPath(uploadDir.toString());

                var iter = items.iterator();
                while (iter.hasNext()) {
                    var path = iter.next();

                    boolean isDir = Files.isDirectory(path);
                    long size = isDir ? 0 : Files.size(path);
                    var mtime = Files.getLastModifiedTime(path);

                    builder.addEntriesBuilder()
                            .setName(path.toString())
                            .setIsDirectory(isDir)
                            .setLastModifiedTime(
                                    LocalDateTime.ofInstant(mtime.toInstant(), ZoneId.systemDefault()).format(DateTimeFormatter.ISO_DATE_TIME))
                            .setSize(size);
                }

                responseObserver.onNext(builder.build());
            }

            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    @Override
    public void listFileStorage(RpcFileStorageId request, StreamObserver<RpcFileStorageContent> responseObserver) {
        try {
            FileStorageId fileStorageId = FileStorageId.of(request.getFileStorageId());

            var storage = fileStorageService.getStorage(fileStorageId);

            var builder = RpcFileStorageContent.newBuilder();


            try (var fs = Files.list(storage.asPath())) {
                fs.filter(Files::isRegularFile)
                        .map(this::createFileModel)
                        .sorted(Comparator.comparing(RpcFileStorageEntry::getName))
                        .forEach(builder::addEntries);
            }

            responseObserver.onNext(builder.build());

            responseObserver.onCompleted();
        }
        catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }

    private RpcFileStorageEntry createFileModel(Path path) {
        try {
            return RpcFileStorageEntry.newBuilder()
                    .setName(path.toFile().getName())
                    .setSize(Files.size(path))
                    .setLastModifiedTime(Files.getLastModifiedTime(path).toInstant().toString())
                    .build();
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void restartExecutorService(Empty request, StreamObserver<Empty> responseObserver) {
        responseObserver.onNext(Empty.getDefaultInstance());
        responseObserver.onCompleted();

        logger.info("Restarting executor service on node {}", serviceConfiguration.node());

        try {
            // Wait for the response to be sent before restarting
            Thread.sleep(Duration.ofSeconds(5));
        }
        catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for restart", e);
        }

        System.exit(0);
    }

    @Override
    public void updateNsfwFilters(RpcUpdateNsfwFilters request, StreamObserver<Empty> responseObserver) {
        logger.info("Got request {}", request);
        try {
            actorControlService.startFrom(ExecutorActor.UPDATE_NSFW_LISTS,
                    new UpdateNsfwFiltersActor.Initial(request.getMsgId()));

            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }
        catch (Exception e) {
            logger.error("Failed to update nsfw filters", e);
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
        }
    }
}
