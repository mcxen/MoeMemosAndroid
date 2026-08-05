package me.mudkip.moememos.data.repository

import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import com.skydoves.sandwich.mapSuccess
import com.skydoves.sandwich.onSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.mudkip.moememos.data.api.MemosV1Api
import me.mudkip.moememos.data.api.MemosV1CreateMemoRequest
import me.mudkip.moememos.data.api.MemosV1Memo
import me.mudkip.moememos.data.api.MemosV1Resource
import me.mudkip.moememos.data.api.MemosV1State
import me.mudkip.moememos.data.api.MemosVisibility
import me.mudkip.moememos.data.api.UpdateMemoRequest
import me.mudkip.moememos.data.api.resolveCurrentUser
import me.mudkip.moememos.data.constant.MoeMemosException
import me.mudkip.moememos.data.model.Account
import me.mudkip.moememos.data.model.Memo
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.model.Resource
import me.mudkip.moememos.data.model.User
import okhttp3.MediaType
import java.time.Instant

private const val PAGE_SIZE = 200

class MemosV1Repository(
    private val memosApi: MemosV1Api,
    private val account: Account.MemosV1
): RemoteRepository() {
    private val remoteUserIdentifier = account.info.remoteIdentifier

    /**
     * null = unknown; true = use parent=users/x (0.22–0.24); false = creator filter (≥0.27)
     */
    private var useParentListing: Boolean? = null

    /**
     * null = unknown; true = /api/v1/resources; false = /api/v1/attachments
     */
    private var useLegacyResources: Boolean? = null

    private fun convertResource(resource: MemosV1Resource): Resource {
        return Resource(
            remoteId = requireNotNull(resource.name),
            date = resource.createTime ?: Instant.now(),
            filename = resource.filename ?: "",
            uri = resource.uri(account.info.host).toString(),
            mimeType = resource.type
        )
    }

    private fun convertMemo(memo: MemosV1Memo): Memo {
        return Memo(
            remoteId = memo.name,
            content = memo.content ?: "",
            date = memo.createTime ?: Instant.now(),
            pinned = memo.pinned ?: false,
            visibility = memo.visibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE,
            resources = memo.resourceList().map { convertResource(it) },
            tags = emptyList(),
            archived = memo.state == MemosV1State.ARCHIVED,
            updatedAt = memo.updateTime
        )
    }

    private suspend fun listMemosByFilter(state: MemosV1State, filter: String): ApiResponse<List<Memo>> {
        var nextPageToken = ""
        val memos = arrayListOf<Memo>()

        do {
            val resp = memosApi.listMemos(PAGE_SIZE, nextPageToken, state, filter)
                .onSuccess { nextPageToken = data.nextPageToken.orEmpty() }
                .mapSuccess { this.memos.map { convertMemo(it) } }
            if (resp is ApiResponse.Success) {
                memos.addAll(resp.data)
            } else {
                return resp
            }
        } while (nextPageToken.isNotEmpty())
        return ApiResponse.Success(memos)
    }

    private suspend fun listMemosByParent(state: MemosV1State, parent: String): ApiResponse<List<Memo>> {
        var nextPageToken = ""
        val memos = arrayListOf<Memo>()

        do {
            val resp = memosApi.listMemos(
                pageSize = PAGE_SIZE,
                pageToken = nextPageToken.ifEmpty { null },
                state = state,
                parent = parent,
            )
                .onSuccess { nextPageToken = data.nextPageToken.orEmpty() }
                .mapSuccess { this.memos.map { convertMemo(it) } }
            if (resp is ApiResponse.Success) {
                memos.addAll(resp.data)
            } else {
                return resp
            }
        } while (nextPageToken.isNotEmpty())
        return ApiResponse.Success(memos)
    }

    private fun getId(identifier: String): String {
        return identifier.substringBefore('|').substringAfterLast('/')
    }

    private fun getName(identifier: String): String {
        return identifier.substringBefore('|')
    }

    private fun userParentName(): String {
        val id = remoteUserIdentifier.trim()
        return if (id.startsWith("users/")) id else "users/$id"
    }

    private suspend fun listCurrentUserMemos(state: MemosV1State): ApiResponse<List<Memo>> {
        when (useParentListing) {
            true -> return listMemosByParent(state, userParentName())
            false -> return listMemosByFilter(state, "creator == \"$remoteUserIdentifier\"")
            null -> {
                // Probe ≥0.27 creator filter; 0.24 returns filter compile error.
                val probe = memosApi.listMemos(
                    pageSize = 1,
                    state = state,
                    filter = "creator == \"$remoteUserIdentifier\"",
                )
                if (probe is ApiResponse.Success) {
                    useParentListing = false
                    return listMemosByFilter(state, "creator == \"$remoteUserIdentifier\"")
                }
                useParentListing = true
                return listMemosByParent(state, userParentName())
            }
        }
    }

    override suspend fun listMemos(): ApiResponse<List<Memo>> {
        return listCurrentUserMemos(MemosV1State.NORMAL)
    }

    override suspend fun listArchivedMemos(): ApiResponse<List<Memo>> {
        return listCurrentUserMemos(MemosV1State.ARCHIVED)
    }

    override suspend fun listWorkspaceMemos(
        pageSize: Int,
        pageToken: String?
    ): ApiResponse<Pair<List<Memo>, String?>> {
        val resp = memosApi.listMemos(pageSize, pageToken, filter = "visibility in [\"PUBLIC\", \"PROTECTED\"]")
        if (resp !is ApiResponse.Success) {
            return resp.mapSuccess { emptyList<Memo>() to null }
        }
        val users = resp.data.memos.mapNotNull { it.creator }.map { getId(it) }.toSet()
        val userResp = coroutineScope {
            users.map { userId ->
                async { memosApi.getUser(userId).getOrNull() }
            }.awaitAll()
        }.filterNotNull()
        val userMap = mapOf(*userResp.map { user -> user.name to user }.toTypedArray())

        return resp
            .mapSuccess { this.memos.map {
                convertMemo(it).copy(
                    creator = it.creator?.let { creator ->
                        userMap[creator]?.let { user ->
                            User(
                                user.name,
                                user.resolvedDisplayName(),
                                user.createTime ?: Instant.now()
                            )
                        }
                    }
                )
            } to this.nextPageToken?.ifEmpty { null } }
    }

    /**
     * Memos ~0.22–0.24 rejects unknown field `attachments` on PATCH (even when empty).
     * Memos ≥0.27 uses `attachments` and may reject `resources`.
     * Never send both; pick the dialect (or probe once).
     *
     * @param linked null = omit resource fields; empty = clear resources; non-empty = set.
     */
    private data class ResourceLinkFields(
        val attachments: List<MemosV1Resource>? = null,
        val resources: List<MemosV1Resource>? = null,
    )

    private fun linkFieldsForDialect(
        linked: List<MemosV1Resource>?,
        legacyResources: Boolean,
    ): ResourceLinkFields {
        if (linked == null) {
            return ResourceLinkFields()
        }
        return if (legacyResources) {
            ResourceLinkFields(resources = linked)
        } else {
            ResourceLinkFields(attachments = linked)
        }
    }

    private suspend fun detectLegacyResourceField(): Boolean {
        useLegacyResources?.let { return it }
        val attachments = memosApi.listAttachments()
        val legacy = attachments !is ApiResponse.Success
        useLegacyResources = legacy
        return legacy
    }

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resourceRemoteIds: List<String>,
        tags: List<String>?,
        createdAt: Instant?
    ): ApiResponse<Memo> {
        val linked = resourceRemoteIds.map { MemosV1Resource(name = getName(it)) }
        val legacy = detectLegacyResourceField()
        val fields = linkFieldsForDialect(linked, legacy)
        val primary = memosApi.createMemo(
            MemosV1CreateMemoRequest(
                content = content,
                visibility = MemosVisibility.fromMemoVisibility(visibility),
                attachments = fields.attachments,
                resources = fields.resources,
                createTime = createdAt
            )
        ).mapSuccess { convertMemo(this) }
        if (primary is ApiResponse.Success || linked.isEmpty()) {
            return primary
        }
        val alt = linkFieldsForDialect(linked, legacyResources = !legacy)
        val secondary = memosApi.createMemo(
            MemosV1CreateMemoRequest(
                content = content,
                visibility = MemosVisibility.fromMemoVisibility(visibility),
                attachments = alt.attachments,
                resources = alt.resources,
                createTime = createdAt
            )
        ).mapSuccess { convertMemo(this) }
        if (secondary is ApiResponse.Success) {
            useLegacyResources = !legacy
        }
        return secondary
    }

    override suspend fun updateMemo(
        remoteId: String,
        content: String?,
        resourceRemoteIds: List<String>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?,
        archived: Boolean?
    ): ApiResponse<Memo> {
        // null = leave resources unchanged; non-null (incl. empty) = replace link set
        val linked = resourceRemoteIds?.map { MemosV1Resource(name = getName(it)) }
        val legacy = detectLegacyResourceField()
        val fields = linkFieldsForDialect(linked, legacy)
        val memoId = getId(remoteId)

        fun buildRequest(link: ResourceLinkFields) = UpdateMemoRequest(
            content = content,
            visibility = visibility?.let { MemosVisibility.fromMemoVisibility(it) },
            pinned = pinned,
            state = archived?.let { isArchived -> if (isArchived) MemosV1State.ARCHIVED else MemosV1State.NORMAL },
            updateTime = Instant.now(),
            attachments = link.attachments,
            resources = link.resources,
        )

        val primary = memosApi.updateMemo(memoId, buildRequest(fields)).mapSuccess { convertMemo(this) }
        if (primary is ApiResponse.Success) {
            return primary
        }

        // Dialect mismatch fallback when a resource field was present.
        if (linked != null) {
            val alt = linkFieldsForDialect(linked, legacyResources = !legacy)
            val secondary = memosApi.updateMemo(memoId, buildRequest(alt)).mapSuccess { convertMemo(this) }
            if (secondary is ApiResponse.Success) {
                useLegacyResources = !legacy
            }
            return secondary
        }
        return primary
    }

    override suspend fun deleteMemo(remoteId: String): ApiResponse<Unit> {
        return memosApi.deleteMemo(getId(remoteId))
    }

    private suspend fun listAllLegacyResources(): ApiResponse<List<Resource>> {
        var nextPageToken: String? = null
        val items = arrayListOf<MemosV1Resource>()
        do {
            val resp = memosApi.listResourcesLegacy(pageSize = PAGE_SIZE, pageToken = nextPageToken)
            if (resp !is ApiResponse.Success) {
                return resp.mapSuccess { emptyList() }
            }
            items.addAll(resp.data.items())
            nextPageToken = resp.data.let {
                // ListResourceResponse has no nextPageToken field in older API when returning all;
                // stop if empty page or no growth. Resources endpoint on 0.24 may return all at once.
                null
            }
            // 0.24 often returns the full list without paging; break after first page.
            break
        } while (!nextPageToken.isNullOrEmpty())
        return ApiResponse.Success(items.map { convertResource(it) })
    }

    override suspend fun listResources(): ApiResponse<List<Resource>> {
        when (useLegacyResources) {
            true -> return listAllLegacyResources()
            false -> {
                return memosApi.listAttachments().mapSuccess { items().map { convertResource(it) } }
            }
            null -> {
                val attachments = memosApi.listAttachments()
                if (attachments is ApiResponse.Success) {
                    useLegacyResources = false
                    return attachments.mapSuccess { items().map { convertResource(it) } }
                }
                useLegacyResources = true
                return listAllLegacyResources()
            }
        }
    }

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        contentLength: Long?,
        openInputStream: () -> java.io.InputStream,
        memoRemoteId: String?
    ): ApiResponse<Resource> {
        val requestBody = StreamingBase64JsonRequestBody(
            filename = filename,
            type = type?.toString() ?: "application/octet-stream",
            memo = memoRemoteId?.let { getName(it) },
            contentLength = contentLength,
            openInputStream = openInputStream
        )
        when (useLegacyResources) {
            true -> return memosApi.createResourceLegacy(requestBody).mapSuccess { convertResource(this) }
            false -> return memosApi.createAttachment(requestBody).mapSuccess { convertResource(this) }
            null -> {
                val modern = memosApi.createAttachment(requestBody)
                if (modern is ApiResponse.Success) {
                    useLegacyResources = false
                    return modern.mapSuccess { convertResource(this) }
                }
                useLegacyResources = true
                return memosApi.createResourceLegacy(requestBody).mapSuccess { convertResource(this) }
            }
        }
    }

    override suspend fun deleteResource(remoteId: String): ApiResponse<Unit> {
        val id = getId(remoteId)
        when (useLegacyResources) {
            true -> return memosApi.deleteResourceLegacy(id)
            false -> return memosApi.deleteAttachment(id)
            null -> {
                val modern = memosApi.deleteAttachment(id)
                if (modern is ApiResponse.Success) {
                    useLegacyResources = false
                    return modern
                }
                useLegacyResources = true
                return memosApi.deleteResourceLegacy(id)
            }
        }
    }

    override suspend fun getCurrentUser(): ApiResponse<User> {
        val resp = memosApi.resolveCurrentUser(account.info.accessToken).mapSuccess {
            User(
                name,
                resolvedDisplayName(),
                createTime ?: Instant.now(),
                avatarUrl = avatarUrl
            )
        }
        if (resp !is ApiResponse.Success) {
            return resp
        }

        val settings = memosApi.getUserSetting(getId(resp.data.identifier))
        if (settings !is ApiResponse.Success) {
            // Settings endpoint is absent on ~0.24 — still succeed with defaults.
            return resp
        }
        return settings.mapSuccess {
            resp.data.copy(
                defaultVisibility = generalSetting?.memoVisibility?.toMemoVisibility() ?: MemoVisibility.PRIVATE
            )
        }
    }
}
