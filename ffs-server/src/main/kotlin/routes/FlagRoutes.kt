@file:Suppress("LocalVariableName", "VariableNaming")

package doist.ffs.routes

import com.squareup.sqldelight.runtime.coroutines.asFlow
import com.squareup.sqldelight.runtime.coroutines.mapToList
import doist.ffs.db.Flag
import doist.ffs.db.capturingLastInsertId
import doist.ffs.db.flags
import doist.ffs.plugins.database
import doist.ffs.serialization.json
import io.ktor.application.Application
import io.ktor.application.application
import io.ktor.application.call
import io.ktor.features.NotFoundException
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.request.accept
import io.ktor.request.receiveParameters
import io.ktor.response.cacheControl
import io.ktor.response.header
import io.ktor.response.respond
import io.ktor.response.respondTextWriter
import io.ktor.routing.Route
import io.ktor.routing.delete
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.put
import io.ktor.routing.routing
import io.ktor.util.getOrFail
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString

fun Application.flagRoutes() {
    routing {
        routeCreateFlag()
        routeGetFlags()
        routeGetFlag()
        routeUpdateFlag()
        routeDeleteFlag()
    }
}

const val PATH_FLAGS = "/flags"
const val PATH_FLAG = "/flag/{id}"

@Suppress("FunctionName")
fun PATH_FLAG(id: Any) = PATH_FLAG.replace("{id}", id.toString())

const val SSE_FIELD_PREFIX_DATA = "data: "
const val SSE_FIELD_PREFIX_ID = "id: "

/**
 * Create a new flag.
 *
 * On success, responds `201 Created` with an empty body.
 *
 * | Parameter         | Required | Description        |
 * | ----------------- | -------- | ------------------ |
 * | `project_id`      | Yes      | ID of the project. |
 * | `name`            | Yes      | Name of the flag.  |
 * | `rule`            | Yes      | Rule of the flag.  |
 */
fun Route.routeCreateFlag() = post(PATH_FLAGS) {
    val params = call.receiveParameters()
    val projectId = params.getOrFail<Long>("project_id")
    val name = params.getOrFail("name")
    val rule = params.getOrFail("rule")
    val id = application.database.capturingLastInsertId {
        flags.insert(project_id = projectId, name = name, rule = rule)
    }
    call.run {
        response.header(HttpHeaders.Location, PATH_FLAG(id))
        respond(HttpStatusCode.Created)
    }
}

/**
 * Lists existing flags for the project.
 *
 * On success, responds `200 OK` with a JSON array containing all flags for the project.
 *
 * | Parameter         | Required | Description        |
 * | ----------------- | -------- | ------------------ |
 * | `project_id`      | Yes      | ID of the project. |
 */
@Suppress("BlockingMethodInNonBlockingContext")
fun Route.routeGetFlags() = get(PATH_FLAGS) {
    val projectId = call.request.queryParameters.getOrFail<Long>("project_id")
    val query = application.database.flags.selectByProject(projectId)
    val sse = call.request.accept()?.let { ContentType.Text.EventStream.match(it) } ?: false
    if (sse) {
        call.response.cacheControl(CacheControl.NoCache(null))
        @Suppress("BlockingMethodInNonBlockingContext")
        var lastUpdatedAt = Instant.DISTANT_PAST
        call.respondTextWriter {
            query.asFlow().mapToList().collect { flags: List<Flag> ->
                val updatedFlags = flags.filter { it.updated_at > lastUpdatedAt }
                write("$SSE_FIELD_PREFIX_DATA${json.encodeToString(updatedFlags)}\n")

                updatedFlags.map(Flag::updated_at).maxOrNull()?.let { lastUpdatedAt = it }
                write("$SSE_FIELD_PREFIX_ID${lastUpdatedAt}\n")

                write("\n")
                flush()
            }
        }
    } else {
        val flags = query.executeAsList()
        call.respond(HttpStatusCode.OK, flags)
    }
}

/**
 * Get an existing flag.
 *
 * On success, responds `200 OK` with a JSON object for the flag.
 *
 * | Parameter | Required | Description     |
 * | --------- | -------- | --------------- |
 * | `id`      | Yes      | ID of the flag. |
 */
fun Route.routeGetFlag() = get(PATH_FLAG) {
    val id = call.parameters.getOrFail<Long>("id")
    val project = application.database.flags.select(id = id).executeAsOneOrNull()
        ?: throw NotFoundException()
    call.respond(HttpStatusCode.OK, project)
}

/**
 * Update a flag.
 *
 * On success, responds `200 OK` with an empty body.
 *
 * | Parameter | Required | Description       |
 * | --------- | -------- | ----------------- |
 * | `id`      | Yes      | ID of the flag.   |
 * | `name`    | No       | Name of the flag. |
 * | `rule`    | No       | Rule of the flag.  |
 */
fun Route.routeUpdateFlag() = put(PATH_FLAG) {
    val id = call.parameters.getOrFail<Long>("id")
    val params = call.receiveParameters()
    val name = params["name"]
    val rule = params["rule"]
    application.database.flags.run {
        val flag = select(id = id).executeAsOneOrNull() ?: throw NotFoundException()
        update(id = id, name = name ?: flag.name, rule = rule ?: flag.rule)
    }
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Delete a flag.
 *
 * On success, responds `201 Created` with an empty body.
 *
 * | Parameter | Required | Description     |
 * | --------- | -------- | --------------- |
 * | `id`      | Yes      | ID of the flag. |
 */
fun Route.routeDeleteFlag() = delete(PATH_FLAG) {
    val id = call.parameters.getOrFail<Long>("id")
    application.database.flags.delete(id = id)
    call.respond(HttpStatusCode.NoContent)
}