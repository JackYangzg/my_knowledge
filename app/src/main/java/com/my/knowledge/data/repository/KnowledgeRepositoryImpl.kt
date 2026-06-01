package com.my.knowledge.data.repository

import com.my.knowledge.data.db.dao.ArchiveRecommendationDao
import com.my.knowledge.data.db.dao.KnowledgeBaseDao
import com.my.knowledge.data.db.dao.KnowledgeItemDao
import com.my.knowledge.data.db.dao.ProcessingTaskDao
import com.my.knowledge.data.db.dao.AiConversationDao
import com.my.knowledge.data.db.dao.AiMessageDao
import com.my.knowledge.data.db.dao.AskCitationDao
import com.my.knowledge.data.db.dao.KnowledgeFragmentDao
import com.my.knowledge.data.db.dao.KnowledgeGraphDao
import com.my.knowledge.data.db.dao.KnowledgeThreadDao
import com.my.knowledge.data.db.dao.KnowledgeThreadLogDao
import com.my.knowledge.data.db.dao.ProcessingTaskLogDao
import com.my.knowledge.data.db.dao.ReviewItemDao
import com.my.knowledge.data.db.dao.SourceManifestDao
import com.my.knowledge.data.db.entity.ArchiveRecommendationEntity
import com.my.knowledge.data.db.entity.AskCitationEntity
import com.my.knowledge.data.db.entity.KnowledgeFragmentEntity
import com.my.knowledge.data.db.entity.KnowledgeCommunityEntity
import com.my.knowledge.data.db.entity.KnowledgeEmbeddingEntity
import com.my.knowledge.data.db.entity.KnowledgeEntityEntity
import com.my.knowledge.data.db.entity.KnowledgeRelationEntity
import com.my.knowledge.data.db.entity.KnowledgeBaseEntity
import com.my.knowledge.data.db.entity.KnowledgeItemEntity
import com.my.knowledge.data.db.entity.ProcessingTaskEntity
import com.my.knowledge.data.db.entity.ProcessingTaskLogEntity
import com.my.knowledge.data.db.entity.ReviewItemEntity
import com.my.knowledge.data.db.entity.SourceManifestEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.repository.ProfileStats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.*

class KnowledgeRepositoryImpl(
    private val kbDao: KnowledgeBaseDao,
    private val itemDao: KnowledgeItemDao,
    private val taskDao: ProcessingTaskDao,
    private val recommendationDao: ArchiveRecommendationDao,
    private val conversationDao: AiConversationDao,
    private val messageDao: AiMessageDao,
    private val threadDao: KnowledgeThreadDao,
    private val threadLogDao: KnowledgeThreadLogDao,
    private val sourceManifestDao: SourceManifestDao,
    private val fragmentDao: KnowledgeFragmentDao,
    private val taskLogDao: ProcessingTaskLogDao,
    private val askCitationDao: AskCitationDao,
    private val graphDao: KnowledgeGraphDao,
    private val reviewItemDao: ReviewItemDao
) : KnowledgeRepository {

    // === KnowledgeBase operations ===
    override fun observeAllBases(): Flow<List<KnowledgeBaseEntity>> = kbDao.observeAll()

    override fun observeItemsByKb(kbId: String, limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observePagedByKb(kbId, limit, offset)

    override fun observeItemCount(kbId: String): Flow<Int> = itemDao.observeCountByKb(kbId)

    override suspend fun createBase(name: String, description: String?, type: String, iconText: String?): KnowledgeBaseEntity {
        val kb = KnowledgeBaseEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            iconText = iconText ?: name.take(1),
            type = type,
            isSystem = type == "unfiled" || type == "system" || type == "inspiration",
            allowDelete = type != "unfiled" && type != "inspiration",
            itemCount = 0,
            sortOrder = if (type == "unfiled") -1 else 0,
            threadStatus = "idle",
            gapStatus = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            deletedAt = null
        )
        kbDao.insert(kb)
        return kb
    }

    override suspend fun ensureDefaultBases() {
        if (kbDao.getByType("unfiled") == null) {
            createBase("未归类", "默认知识存放处", type = "unfiled", iconText = "未")
        }
        if (kbDao.getByType("inspiration") == null) {
            createBase("灵感空间", "灵感记录与碎片收集", type = "inspiration", iconText = "灵")
        }
    }

    override suspend fun getBaseById(id: String): KnowledgeBaseEntity? = kbDao.getById(id)

    override suspend fun getBaseByName(name: String): KnowledgeBaseEntity? = kbDao.getByName(name)

    override suspend fun updateBase(base: KnowledgeBaseEntity) {
        kbDao.update(base.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteBase(id: String, moveToUnfiled: Boolean) {
        val base = kbDao.getById(id) ?: return

        if (!base.allowDelete) {
            throw IllegalArgumentException("Cannot delete system knowledge base")
        }

        val items = itemDao.getAllByKb(id)
        if (moveToUnfiled) {
            val unfiled = getUnfiledBase() ?: createBase("未归类", "默认知识存放处", "unfiled", "未")
            items.forEach { item ->
                itemDao.moveToBase(item.id, unfiled.id, System.currentTimeMillis())
            }
            itemDao.updateItemCount(unfiled.id)
        } else {
            items.forEach { item ->
                itemDao.hardDelete(item.id)
            }
        }

        kbDao.hardDelete(id)
    }

    // === KnowledgeItem operations ===
    override suspend fun createItem(item: KnowledgeItemEntity): KnowledgeItemEntity {
        itemDao.insert(item)
        rebuildFragmentsForItem(item)
        itemDao.updateItemCount(item.knowledgeBaseId)
        return item
    }

    override suspend fun createUnfiledItemFromNote(
        noteId: String?,
        title: String,
        content: String,
        sourceType: String
    ): KnowledgeItemEntity {
        ensureDefaultBases()
        val unfiled = getUnfiledBase() ?: createBase("未归类", "默认知识存放处", "unfiled", "未")
        val now = System.currentTimeMillis()
        val item = KnowledgeItemEntity(
            id = UUID.randomUUID().toString(),
            knowledgeBaseId = unfiled.id,
            title = title.trim().ifBlank { "灵感 $now" },
            contentMarkdown = content,
            excerpt = content.trim().take(120),
            sourceType = sourceType,
            status = KnowledgeItemEntity.STATUS_UNFILED,
            contentHash = calculateContentHash(content),
            summary = null,
            tagsJson = "[]",
            rawNoteId = noteId,
            importance = 1,
            createdAt = now,
            updatedAt = now,
            processedAt = null,
            deletedAt = null
        )
        createItem(item)
        val source = registerTextSource("knowledge_item", item.id, sourceType, content)
        rebuildFragmentsForItem(item, source.id)
        createProcessingTask(
            ProcessingTaskEntity(
                id = UUID.randomUUID().toString(),
                targetType = "knowledge_item",
                targetId = item.id,
                taskType = "parse_analyze_generate",
                status = "pending",
                priority = 10,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null
            )
        )
        appendProcessingLog(
            ProcessingTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = null,
                targetType = "knowledge_item",
                targetId = item.id,
                stage = "source_register",
                status = source.status,
                message = "Registered source manifest and queued processing pipeline",
                createdAt = now
            )
        )
        return item
    }

    override suspend fun getItemById(id: String): KnowledgeItemEntity? = itemDao.getById(id)

    override suspend fun getItemBySourceId(sourceId: String): KnowledgeItemEntity? =
        itemDao.getBySourceId(sourceId)

    override fun observeProcessedItemsBySource(sourceId: String): Flow<List<KnowledgeItemEntity>> =
        itemDao.observeProcessedBySource(sourceId)

    override suspend fun updateItem(item: KnowledgeItemEntity) {
        val updated = item.copy(updatedAt = System.currentTimeMillis())
        itemDao.update(updated)
        rebuildFragmentsForItem(updated)
    }

    override suspend fun deleteItem(id: String, softDelete: Boolean) {
        val item = itemDao.getById(id) ?: return
        if (softDelete) {
            itemDao.softDelete(id, System.currentTimeMillis())
        } else {
            permanentDeleteItem(id)
        }
        itemDao.updateItemCount(item.knowledgeBaseId)
    }

    override suspend fun permanentDeleteItem(id: String) {
        taskDao.deleteByTarget("knowledge_item", id)
        recommendationDao.deleteByItemId(id)
        fragmentDao.deleteByItemId(id)
        taskLogDao.deleteByTarget("knowledge_item", id)
        itemDao.hardDelete(id)
    }

    override suspend fun restoreItem(id: String) {
        val item = itemDao.getByIdIncludeDeleted(id) ?: return
        itemDao.restore(id, System.currentTimeMillis())
        itemDao.updateItemCount(item.knowledgeBaseId)
    }

    override fun observeDeletedItems(): Flow<List<KnowledgeItemEntity>> = itemDao.observeDeletedItems()

    override fun observeDeletedItemsPaged(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observeDeletedItemsPaged(limit, offset)

    override fun observeDeletedItemCount(): Flow<Int> = itemDao.observeDeletedItemCount()

    override suspend fun restoreItems(ids: List<String>) {
        val timestamp = System.currentTimeMillis()
        ids.forEach { id ->
            itemDao.restore(id, timestamp)
        }
        // Update item counts for all affected bases
        kbDao.getAllIds().forEach { kbId ->
            itemDao.updateItemCount(kbId)
        }
    }

    override suspend fun permanentDeleteItems(ids: List<String>) {
        ids.forEach { id ->
            taskDao.deleteByTarget("knowledge_item", id)
            recommendationDao.deleteByItemId(id)
        }
        itemDao.hardDeleteItems(ids)
    }

    override suspend fun moveItemToBase(itemId: String, targetKbId: String) {
        val item = itemDao.getById(itemId) ?: return
        val oldKbId = item.knowledgeBaseId
        val targetBase = kbDao.getById(targetKbId)
        itemDao.moveToBase(itemId, targetKbId, System.currentTimeMillis())
        if (targetBase?.type != "unfiled") {
            itemDao.update(
                item.copy(
                    knowledgeBaseId = targetKbId,
                    status = KnowledgeItemEntity.STATUS_ARCHIVED,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        itemDao.updateItemCount(oldKbId)
        itemDao.updateItemCount(targetKbId)
        if (targetBase?.type != "unfiled") rebuildGraphForBase(targetKbId)
    }

    // === Unfiled operations ===
    override fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observeUnfiledItems(limit, offset)

    override fun observeUnfiledItemCount(): Flow<Int> = itemDao.observeUnfiledItemCount()

    override suspend fun getUnfiledBase(): KnowledgeBaseEntity? = kbDao.getByType("unfiled")

    // === Content Hash ===
    override fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun registerTextSource(
        ownerType: String,
        ownerId: String,
        sourceType: String,
        content: String,
        sourceUri: String?
    ): SourceManifestEntity {
        val now = System.currentTimeMillis()
        val hash = calculateContentHash(content)
        val duplicate = sourceManifestDao.getFirstByHash(hash)
        val source = SourceManifestEntity(
            id = UUID.randomUUID().toString(),
            sourceUri = sourceUri,
            sourceType = sourceType,
            localPath = null,
            contentHash = hash,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            mimeType = "text/markdown",
            status = if (duplicate == null) SourceManifestEntity.STATUS_NEW else SourceManifestEntity.STATUS_DUPLICATED,
            ownerType = ownerType,
            ownerId = ownerId,
            duplicateOfSourceId = duplicate?.id,
            createdAt = now,
            updatedAt = now
        )
        sourceManifestDao.insert(source)
        return source
    }

    override fun observeSources(ownerType: String, ownerId: String): Flow<List<SourceManifestEntity>> =
        sourceManifestDao.observeByOwner(ownerType, ownerId)

    override fun observeFragments(itemId: String): Flow<List<KnowledgeFragmentEntity>> =
        fragmentDao.observeByItem(itemId)

    override suspend fun rebuildFragmentsForItem(
        item: KnowledgeItemEntity,
        sourceManifestId: String?
    ): List<KnowledgeFragmentEntity> {
        fragmentDao.deleteByItemId(item.id)
        val blocks = item.contentMarkdown
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val sourceBlocks = if (blocks.isEmpty() && item.contentMarkdown.isNotBlank()) listOf(item.contentMarkdown.trim()) else blocks
        var cursor = 0
        val now = System.currentTimeMillis()
        val fragments = sourceBlocks.take(32).map { block ->
            val start = item.contentMarkdown.indexOf(block, startIndex = cursor).coerceAtLeast(cursor)
            val end = (start + block.length).coerceAtMost(item.contentMarkdown.length)
            cursor = end
            KnowledgeFragmentEntity(
                id = UUID.randomUUID().toString(),
                itemId = item.id,
                knowledgeBaseId = item.knowledgeBaseId,
                content = block.take(1200),
                summary = item.summary,
                tagsJson = item.tagsJson,
                sourceRef = item.rawNoteId,
                sourceManifestId = sourceManifestId,
                startOffset = start,
                endOffset = end,
                createdAt = now
            )
        }
        if (fragments.isNotEmpty()) fragmentDao.insertAll(fragments)
        graphDao.deleteEmbeddingsByItem(item.id)
        if (fragments.isNotEmpty()) {
            graphDao.upsertEmbeddings(fragments.map { fragment ->
                KnowledgeEmbeddingEntity(
                    id = UUID.randomUUID().toString(),
                    itemId = item.id,
                    fragmentId = fragment.id,
                    knowledgeBaseId = item.knowledgeBaseId,
                    embeddingJson = localEmbeddingJson(fragment.content),
                    model = "local-token-hash-v1",
                    dimensions = 16,
                    contentHash = calculateContentHash(fragment.content),
                    updatedAt = now
                )
            })
        }
        return fragments
    }

    override suspend fun rebuildGraphForBase(kbId: String) {
        val items = itemDao.getAllByKb(kbId).filter { it.deletedAt == null }
        val now = System.currentTimeMillis()
        graphDao.clearEntities(kbId)
        graphDao.clearRelations(kbId)
        graphDao.clearCommunities(kbId)

        val wikiPages = items.filter { it.sourceType.startsWith("wiki_") }
            .ifEmpty { items }
        val pageMeta = wikiPages.map { item ->
            WikiPageMeta(
                item = item,
                title = frontMatterValue(item.contentMarkdown, "title") ?: item.title,
                type = frontMatterValue(item.contentMarkdown, "type") ?: when (item.sourceType) {
                    "wiki_entity" -> "entity"
                    "wiki_concept" -> "concept"
                    "wiki_source" -> "source"
                    else -> "concept"
                },
                sources = frontMatterList(item.contentMarkdown, "sources"),
                links = extractWikiLinks(item.contentMarkdown)
            )
        }

        val entities = pageMeta.map { page ->
            KnowledgeEntityEntity(
                id = UUID.randomUUID().toString(),
                knowledgeBaseId = kbId,
                name = page.title,
                type = page.type,
                aliasesJson = "[]",
                sourceItemIdsJson = "[\"${page.item.id}\"]",
                weight = (1 + page.links.size + page.sources.size).toFloat(),
                createdAt = now,
                updatedAt = now
            )
        }.sortedByDescending { it.weight }.take(200)
        graphDao.upsertEntities(entities)

        val byName = entities.associateBy { it.name.lowercase(Locale.ROOT) }
        val pageByName = pageMeta.associateBy { it.title.lowercase(Locale.ROOT) }
        val relations = mutableListOf<KnowledgeRelationEntity>()

        pageMeta.forEach { page ->
            val from = byName[page.title.lowercase(Locale.ROOT)] ?: return@forEach
            page.links.forEach { link ->
                val to = byName[link.lowercase(Locale.ROOT)] ?: return@forEach
                if (from.id != to.id) {
                    relations += KnowledgeRelationEntity(
                        id = UUID.randomUUID().toString(),
                        knowledgeBaseId = kbId,
                        fromEntityId = from.id,
                        toEntityId = to.id,
                        relationType = "wikilink",
                        evidenceItemIdsJson = "[\"${page.item.id}\"]",
                        confidence = 1.0f,
                        createdAt = now,
                        updatedAt = now
                    )
                }
            }
        }

        val relationKeys = relations.map { it.fromEntityId to it.toEntityId }.toMutableSet()
        for (i in pageMeta.indices) {
            for (j in i + 1 until pageMeta.size) {
                val left = pageMeta[i]
                val right = pageMeta[j]
                val overlap = left.sources.intersect(right.sources.toSet())
                if (overlap.isEmpty()) continue
                val from = byName[left.title.lowercase(Locale.ROOT)] ?: continue
                val to = byName[right.title.lowercase(Locale.ROOT)] ?: continue
                val key = from.id to to.id
                if (key in relationKeys) continue
                relations += KnowledgeRelationEntity(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = kbId,
                    fromEntityId = from.id,
                    toEntityId = to.id,
                    relationType = "source_overlap",
                    evidenceItemIdsJson = "[\"${left.item.id}\",\"${right.item.id}\"]",
                    confidence = 0.8f,
                    createdAt = now,
                    updatedAt = now
                )
                relationKeys += key
            }
        }
        graphDao.upsertRelations(relations.take(200))

        val communities = pageMeta.groupBy { page ->
            page.sources.firstOrNull() ?: page.type
        }
            .filter { it.value.size >= 2 }
            .map { (key, group) ->
                KnowledgeCommunityEntity(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = kbId,
                    name = "来源群 $key",
                    entityIdsJson = group.mapNotNull { byName[it.title.lowercase(Locale.ROOT)] }
                        .joinToString(",", "[", "]") { "\"${it.id}\"" },
                    summary = group.take(6).joinToString("、") { it.title },
                    createdAt = now,
                    updatedAt = now
                )
            }
        graphDao.upsertCommunities(communities)
    }

    override fun observeKnowledgeEntities(kbId: String): Flow<List<KnowledgeEntityEntity>> =
        graphDao.observeEntities(kbId)

    override fun observeAllKnowledgeEntities(): Flow<List<KnowledgeEntityEntity>> =
        graphDao.observeAllEntities()

    override fun observeKnowledgeRelations(kbId: String): Flow<List<KnowledgeRelationEntity>> =
        graphDao.observeRelations(kbId)

    override fun observeAllKnowledgeRelations(): Flow<List<KnowledgeRelationEntity>> =
        graphDao.observeAllRelations()

    override fun observeKnowledgeCommunities(kbId: String): Flow<List<KnowledgeCommunityEntity>> =
        graphDao.observeCommunities(kbId)

    override fun observeAllKnowledgeCommunities(): Flow<List<KnowledgeCommunityEntity>> =
        graphDao.observeAllCommunities()

    // === ProcessingTask operations ===
    override suspend fun createProcessingTask(task: ProcessingTaskEntity): ProcessingTaskEntity {
        taskDao.insert(task)
        return task
    }

    override suspend fun updateProcessingTask(task: ProcessingTaskEntity) {
        taskDao.update(task.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun getProcessingTask(taskId: String): ProcessingTaskEntity? =
        taskDao.getById(taskId)

    override suspend fun getPendingTask(targetType: String, targetId: String): ProcessingTaskEntity? =
        taskDao.getPendingTask(targetType, targetId)

    override suspend fun getActiveTasks(): Flow<List<ProcessingTaskEntity>> = taskDao.observeActiveTasks()

    override suspend fun retryTask(taskId: String) {
        taskDao.retryTask(taskId, System.currentTimeMillis())
    }

    override suspend fun retryProcessingForItem(itemId: String) {
        val item = itemDao.getById(itemId) ?: return
        val now = System.currentTimeMillis()
        item.sourceId?.let { sourceId ->
            taskDao.retryBySource(sourceId, now)
            itemDao.updateStatusBySourceId(sourceId, KnowledgeItemEntity.STATUS_PROCESSING, now)
        }
    }

    override suspend fun cancelTask(taskId: String) {
        taskDao.cancelTask(taskId, System.currentTimeMillis())
    }

    override suspend fun appendProcessingLog(log: ProcessingTaskLogEntity) {
        taskLogDao.insert(log)
    }

    override fun observeProcessingLogs(targetType: String, targetId: String): Flow<List<ProcessingTaskLogEntity>> =
        taskLogDao.observeByTarget(targetType, targetId)

    override fun observePendingReviews(): Flow<List<ReviewItemEntity>> =
        reviewItemDao.observePending()

    override suspend fun resolveReview(reviewId: String, status: String) {
        reviewItemDao.resolve(reviewId, status, System.currentTimeMillis())
    }

    override fun observeUnfiledWorkCount(): Flow<Int> =
        itemDao.observeCountByStatuses(
            listOf(
                KnowledgeItemEntity.STATUS_UNFILED,
                KnowledgeItemEntity.STATUS_PROCESSING,
                KnowledgeItemEntity.STATUS_NEED_REVIEW,
                KnowledgeItemEntity.STATUS_RECOMMEND_READY,
                KnowledgeItemEntity.STATUS_FAILED
            )
        )

    override fun observeProfileStats(): Flow<ProfileStats> =
        combine(
            kbDao.observeActiveBaseCount(),
            itemDao.observeActiveItemCount(),
            graphDao.observeEntityCount(),
            graphDao.observeConceptCount()
        ) { baseCount, itemCount, entityCount, conceptCount ->
            ProfileStats(baseCount, itemCount, entityCount, conceptCount)
        }

    // === ArchiveRecommendation operations ===
    override suspend fun createArchiveRecommendation(recommendation: ArchiveRecommendationEntity): ArchiveRecommendationEntity {
        recommendationDao.insert(recommendation)
        return recommendation
    }

    override suspend fun getRecommendationForItem(itemId: String): ArchiveRecommendationEntity? =
        recommendationDao.getByItemId(itemId)

    override suspend fun acceptRecommendation(recommendationId: String) {
        val recommendation = recommendationDao.getById(recommendationId) ?: return
        recommendationDao.updateStatus(recommendationId, "accepted", System.currentTimeMillis())
        
        // Move item to recommended base
        recommendation.recommendedKnowledgeBaseId?.let { kbId ->
            val item = itemDao.getById(recommendation.itemId)
            itemDao.moveToBase(recommendation.itemId, kbId, System.currentTimeMillis())
            if (item != null) {
                itemDao.update(item.copy(
                    knowledgeBaseId = kbId,
                    status = KnowledgeItemEntity.STATUS_ARCHIVED,
                    updatedAt = System.currentTimeMillis()
                ))
                itemDao.updateItemCount(item.knowledgeBaseId)
            }
            itemDao.updateItemCount(kbId)
            val base = kbDao.getById(kbId)
            if (base?.type != "unfiled") rebuildGraphForBase(kbId)
        }
    }

    override suspend fun rejectRecommendation(recommendationId: String) {
        recommendationDao.updateStatus(recommendationId, "rejected", System.currentTimeMillis())
    }

    override fun observePendingRecommendations(): Flow<List<ArchiveRecommendationEntity>> =
        recommendationDao.observePending()

    // === Batch operations ===
    override suspend fun batchUpdateItemCounts(baseIds: List<String>) {
        baseIds.forEach { kbId ->
            itemDao.updateItemCount(kbId)
        }
    }

    override suspend fun exportMarkdownBundle(): String {
        val bases = kbDao.getAllIds().mapNotNull { kbDao.getById(it) }.associateBy { it.id }
        val items = itemDao.getAllActive(limit = 10_000, offset = 0)
        return buildString {
            appendLine("# My Knowledge Export")
            appendLine()
            appendLine("Generated at: ${System.currentTimeMillis()}")
            appendLine()
            items.groupBy { it.knowledgeBaseId }.forEach { (kbId, group) ->
                appendLine("## ${bases[kbId]?.name ?: "未知知识库"}")
                appendLine()
                group.forEach { item ->
                    appendLine("### ${item.title}")
                    appendLine()
                    if (!item.summary.isNullOrBlank()) {
                        appendLine("> ${item.summary}")
                        appendLine()
                    }
                    appendLine(item.contentMarkdown)
                    appendLine()
                }
            }
        }
    }

    // === AI Conversation operations ===
    override fun observeConversations(scopeType: String, scopeId: String): Flow<List<AiConversationEntity>> =
        conversationDao.observeByScope(scopeType, scopeId)

    override suspend fun createConversation(conversation: AiConversationEntity): AiConversationEntity {
        conversationDao.insert(conversation)
        return conversation
    }

    override suspend fun getConversation(id: String): AiConversationEntity? =
        conversationDao.getById(id)

    override suspend fun updateConversation(conversation: AiConversationEntity) {
        conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
    }

    // === AI Message operations ===
    override fun observeMessages(conversationId: String): Flow<List<AiMessageEntity>> =
        messageDao.observeByConversation(conversationId)

    override suspend fun createMessage(message: AiMessageEntity): AiMessageEntity {
        messageDao.insert(message)
        return message
    }

    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<AiMessageEntity> =
        messageDao.getRecentMessages(conversationId, limit)

    override suspend fun replaceCitationsForMessage(messageId: String, citations: List<AskCitationEntity>) {
        askCitationDao.deleteByMessage(messageId)
        if (citations.isNotEmpty()) askCitationDao.insertAll(citations)
    }

    override fun observeCitations(messageId: String): Flow<List<AskCitationEntity>> =
        askCitationDao.observeByMessage(messageId)

    // === Knowledge Thread operations ===
    override suspend fun getThreadByKb(kbId: String): KnowledgeThreadEntity? =
        threadDao.getByKb(kbId)

    override suspend fun saveThread(thread: KnowledgeThreadEntity) {
        val existing = threadDao.getByKb(thread.knowledgeBaseId)
        if (existing != null) {
            threadDao.update(thread.copy(id = existing.id))
        } else {
            threadDao.insert(thread)
        }
    }

    override fun observeThreadLogs(threadId: String): Flow<List<KnowledgeThreadLogEntity>> =
        threadLogDao.observeByThread(threadId)

    override suspend fun appendThreadLog(log: KnowledgeThreadLogEntity) {
        threadLogDao.insert(log)
    }

    private fun localEmbeddingJson(content: String): String {
        val vector = FloatArray(16)
        tokenize(content).forEach { token ->
            val bucket = (token.hashCode() and Int.MAX_VALUE) % vector.size
            vector[bucket] += 1f
        }
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        return vector.joinToString(",", "[", "]") { "%.4f".format(Locale.US, it / norm) }
    }

    private fun extractConcepts(text: String): List<String> =
        tokenize(text)
            .filter { it.length in 2..24 }
            .filterNot { it.all { c -> c.isDigit() } }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
            .map { it.first }
            .take(12)

    private fun tokenize(text: String): List<String> {
        val cleaned = text
            .replace(Regex("[\\[\\]{}\"#*`~!?.:;，。！？、（）()<>/\\\\|]+"), " ")
            .lowercase(Locale.ROOT)
        return cleaned.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private data class WikiPageMeta(
        val item: KnowledgeItemEntity,
        val title: String,
        val type: String,
        val sources: List<String>,
        val links: List<String>
    )

    private fun frontMatterValue(markdown: String, key: String): String? {
        val frontMatter = markdown.substringAfter("---", "").substringBefore("---", "")
        return frontMatter.lines()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(":")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() && !it.startsWith("[") }
    }

    private fun frontMatterList(markdown: String, key: String): List<String> {
        val frontMatter = markdown.substringAfter("---", "").substringBefore("---", "")
        val line = frontMatter.lines().firstOrNull { it.trimStart().startsWith("$key:") } ?: return emptyList()
        return line.substringAfter("[", "").substringBefore("]", "")
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
    }

    private fun extractWikiLinks(markdown: String): List<String> =
        Regex("\\[\\[([^\\]]+)]]")
            .findAll(markdown)
            .map { it.groupValues[1].substringBefore("|").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
}
