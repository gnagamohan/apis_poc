package auth;

import org.apache.pekko.actor.ActorSystem;
import play.libs.concurrent.CustomExecutionContext;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Thread pool for blocking work (JDBC, BCrypt), configured by "database.dispatcher". */
@Singleton
public class DatabaseExecutionContext extends CustomExecutionContext {
    @Inject
    public DatabaseExecutionContext(ActorSystem actorSystem) {
        super(actorSystem, "database.dispatcher");
    }
}
