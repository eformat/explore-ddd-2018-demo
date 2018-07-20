package io.openinnovationlabs.ddd;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The idea here is to make Verticles more actor like, where every Aggregate instance has it's mailbox which follows
 * a simple convention to remove boiler plate. This is inspired by https://vaughnvernon.co/?p=780. In this case, the
 * mailbox accepts Commands, not any arbitrary message.
 * <p>
 * Reflection modelled after https://github.com/eventuate-clients/eventuate-client-java/blob/master/eventuate-client-java/src/main/java/io/eventuate/ReflectiveMutableCommandProcessingAggregate.java
 * <p>
 * TODO need example of event subscription between aggregates. should be straightforward...
 * TODO garbage collect aggregates that have no been used with a simple timer that is reset on each command/event
 */
public abstract class Aggregate extends AbstractVerticle {

    private static Logger LOGGER = LoggerFactory.getLogger(Aggregate.class);
    protected long eventStreamIndex = 0;
    protected Boolean replaying;
    private String id;
    private DomainModel domainModel; // TODO perhaps this should be a singleton?

    /**
     * idea here is give subclasses common initialization but with an extension point in start(). Subclasses
     * shouldn't be doing resource intensive stuff in start()
     */
    @Override
    public void start(Future<Void> startFuture) throws Exception {
        String id = context.config().getString("id");
        if (id == null || id.isEmpty()) {
            startFuture.fail("id must be set in DeploymentOptions");
        } else {
            this.id = id;
        }

        if (context.config().containsKey("replay")) {
            replaying = context.config().getBoolean("replay");
        } else {
            replaying = false;
        }

        this.domainModel = new DomainModel(vertx);


        initializeMessageConsumers();

        start();
        startFuture.complete();
    }

    private void initializeMessageConsumers() {
        String commandAddress = String.format(DomainModel.COMMAND_ADDRESS_FORMAT, this.getClass().getSimpleName(), this.id);
        vertx.eventBus().<JsonObject>consumer(commandAddress).handler(this::handleCommandMessage);
    }

    /**
     * the order of operations in this method is handled differently by Akka than what Vaughn Vernon suggests in
     * https://vaughnvernon.co/?page_id=168#iddd
     * https://doc.akka.io/docs/akka/2.5/persistence.html
     * <p>
     * the current approach where persistence happens after applying events suggests that non transactional,
     * synchronous interaction with remote services (e.g. http), which is owned by this bounded context should be
     * implemented with adapters that subscribe to the vertx event stream and thus would receive the events only
     * after they were successfully persisted. external bounded contexts will follow the event stream in kafka
     * <p>
     * TODO what does eventuate do?
     * TODO what is the right way?
     */
    private void handleCommandMessage(Message<JsonObject> message) {
        Command command = (Command) mapToCommandObject(message);
        try {
            final List<Event> events = processCommand(command);
            applyEvents(events);

            if (events == null || events.size() == 0) {
                LOGGER.debug(String.format("%s :: 0 events applied. Command processing complete.", command.aggregateIdentity()));
                message.reply(JsonObject.mapFrom(new CommandProcessingResponse(new ArrayList<>())));
            } else if (command instanceof ReplayEventsCommand) {
                // do not persist  events, as we only replayed the past
                this.replaying = false;
                LOGGER.debug(String.format("%s :: Replay complete.", command.aggregateIdentity()));
                message.reply(JsonObject.mapFrom(new CommandProcessingResponse(events)));
            } else {
                domainModel.persistEvents(events).setHandler(ar -> {
                    LOGGER.debug(String.format("%s :: Command processing complete.", command.aggregateIdentity()));
                    message.reply(JsonObject.mapFrom(new CommandProcessingResponse(events)));
                });
            }
        } catch (DomainModelException e) {
            LOGGER.error(String.format("%s :: Command processing failed. %s", command.aggregateIdentity(), e.getLocalizedMessage()));
            message.reply(JsonObject.mapFrom(new CommandProcessingResponse(e)));
        }


    }

    private Object mapToCommandObject(Message<JsonObject> message) {
        LOGGER.trace(message.body().toString());
        String commandClassname = message.headers().get("commandClassname");
        Class<?> commandClass = null;
        try {
            commandClass = Class.forName(commandClassname);
        } catch (ClassNotFoundException e1) {
            e1.printStackTrace();
        }
        return message.body().mapTo(commandClass);
    }

    private List<Event> processCommand(Command command) throws DomainModelException {
        LOGGER.debug(String.format("%s :: Command received %s  ",
                command.aggregateIdentity(),
                command.getClass().getSimpleName())
        );
        try {
            List<Event> events = (List<Event>) getClass().getMethod("process", command.getClass()).invoke(this, command);
            return events;
        } catch (IllegalAccessException e) {
            throw new DomainModelException(e);
        } catch (InvocationTargetException e) {
            throw new DomainModelException(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new DomainModelException(e);
        }

    }


    private void applyEvents(List<Event> events) {
        if (events == null || events.size() == 0) {
            return;
        }
        for (Event e : events) {
            try {
                getClass().getMethod("apply", e.getClass()).invoke(this, e);
            } catch (IllegalAccessException e1) {
                e1.printStackTrace();
            } catch (InvocationTargetException e1) {
                e1.printStackTrace();
            } catch (NoSuchMethodException e1) {
                e1.printStackTrace();
            }
        }

        LOGGER.debug(String.format("%s :: %d event(s) applied ", events.get(0).getAggregateIdentity(), events.size()));
    }

    public List<Event> process(ReplayEventsCommand command) {
        if (replaying) {
            List<Event> events = new ArrayList<>(command.events);
            return events;
        } else {
            // TODO better exception handling here
            throw new IllegalStateException("aggregate must be in replay mode to accept replay command");
        }
    }

}
