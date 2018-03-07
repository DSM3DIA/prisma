package com.prisma.deploy.specutils

import akka.actor.ActorSystem
import com.prisma.deploy.database.persistence.MigrationPersistence
import com.prisma.deploy.migration.MigrationStepMapperImpl
import com.prisma.deploy.migration.migrator.{MigrationApplierImpl, Migrator}
import com.prisma.deploy.migration.mutactions.FailingAnyMutactionExecutor
import com.prisma.shared.models._
import com.prisma.utils.await.AwaitUtils
import slick.jdbc.MySQLProfile.backend.DatabaseDef

import scala.concurrent.Future

case class TestMigrator(
    clientDatabase: DatabaseDef,
    internalDb: DatabaseDef,
    migrationPersistence: MigrationPersistence
)(implicit val system: ActorSystem)
    extends Migrator
    with AwaitUtils {
  import system.dispatcher

  // For tests, the schedule directly does all the migration work to remove the asynchronous component
  override def schedule(projectId: String, nextSchema: Schema, steps: Vector[MigrationStep], functions: Vector[Function]): Future[Migration] = {
    val stepMapper = MigrationStepMapperImpl(projectId)
    val applier    = MigrationApplierImpl(migrationPersistence, clientDatabase, stepMapper, FailingAnyMutactionExecutor)

    val result: Future[Migration] = for {
      savedMigration <- migrationPersistence.create(Migration(projectId, nextSchema, steps, functions))
      lastMigration  <- migrationPersistence.getLastMigration(projectId)
      applied <- applier.apply(lastMigration.get.schema, savedMigration).flatMap { result =>
                  if (result.succeeded) {
                    migrationPersistence.updateMigrationStatus(savedMigration.id, MigrationStatus.Success).map { _ =>
                      savedMigration.copy(status = MigrationStatus.Success)
                    }
                  } else {
                    Future.failed(new Exception("Fatal: apply resulted in an error"))
                  }
                }
    } yield {
      applied
    }

    result.await
    result
  }
}
