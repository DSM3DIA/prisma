package cool.graph.deploy.migration.migrator

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import cool.graph.deploy.database.persistence.MigrationPersistence
import cool.graph.deploy.migration.MigrationApplierJob
import cool.graph.shared.models.{Migration, MigrationStep, Project}
import slick.jdbc.MySQLProfile.backend.DatabaseDef

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._

case class AsyncMigrator(clientDatabase: DatabaseDef, migrationPersistence: MigrationPersistence)(
    implicit val system: ActorSystem,
    materializer: ActorMaterializer
) extends Migrator {
  import system.dispatcher

  val job                 = system.actorOf(Props(MigrationApplierJob(clientDatabase, migrationPersistence)))
  val deploymentScheduler = system.actorOf(Props(DeploymentSchedulerActor()(migrationPersistence)))

  implicit val timeout = new Timeout(30.seconds)

  (deploymentScheduler ? Initialize).onComplete {
    case Success(_) =>
      println("Deployment worker initialization complete.")

    case Failure(err) =>
      println(s"Fatal error during deployment worker initialization: $err")
      sys.exit(-1)
  }

  override def schedule(nextProject: Project, steps: Vector[MigrationStep]): Future[Migration] = {
    (deploymentScheduler ? Schedule(nextProject, steps)).mapTo[Migration]
  }
}
