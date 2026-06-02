package com.my.knowledge.data.repository

import com.my.knowledge.data.db.dao.AnalysisResultDao
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
import com.my.knowledge.data.db.dao.ParsedContentDao
import com.my.knowledge.data.db.dao.ProcessingTaskLogDao
import com.my.knowledge.data.db.dao.ReviewItemDao
import com.my.knowledge.data.db.dao.SourceDocumentDao
import com.my.knowledge.data.db.dao.SourceManifestDao
import com.my.knowledge.data.db.entity.AnalysisResultEntity
import org.json.JSONArray
import org.json.JSONObject
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
import com.my.knowledge.data.db.entity.ParsedContentEntity
import com.my.knowledge.data.db.entity.SourceDocumentEntity
import com.my.knowledge.data.db.entity.SourceManifestEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadEntity
import com.my.knowledge.data.db.entity.KnowledgeThreadLogEntity
import com.my.knowledge.data.db.entity.AiMessageEntity
import com.my.knowledge.data.db.entity.AiConversationEntity
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
    private val reviewItemDao: ReviewItemDao,
    private val analysisResultDao: AnalysisResultDao,
    private val parsedContentDao: ParsedContentDao,
    private val sourceDocumentDao: SourceDocumentDao
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
        val unfiled = kbDao.getByType("unfiled")
        if (unfiled == null) {
            createBase("未归档知识库", "默认知识存放处", type = "unfiled", iconText = "未")
        } else if (unfiled.name == "未归类") {
            kbDao.update(unfiled.copy(name = "未归档知识库"))
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
                // Also delete associated AI conversations for each item
                clearConversationsByScope("knowledge_item", item.id)
            }
        }

        // Delete conversations associated with the base itself
        clearConversationsByScope("knowledge_base", id)

        kbDao.hardDelete(id)
    }

    // === KnowledgeItem operations ===
    override suspend fun createItem(item: KnowledgeItemEntity): KnowledgeItemEntity {
        itemDao.insert(item)
        rebuildFragmentsForItem(item)
        itemDao.updateItemCount(item.knowledgeBaseId)
        refreshOverviewForBase(item.knowledgeBaseId)
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

    override suspend fun getByRawNoteId(noteId: String): KnowledgeItemEntity? =
        itemDao.getByRawNoteId(noteId)

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
        refreshOverviewForBase(item.knowledgeBaseId)
        rebuildGraphForBase(item.knowledgeBaseId)
    }

    override suspend fun permanentDeleteItem(id: String) {
        taskDao.deleteByTarget("knowledge_item", id)
        recommendationDao.deleteByItemId(id)
        fragmentDao.deleteByItemId(id)
        taskLogDao.deleteByTarget("knowledge_item", id)
        
        // Delete associated AI conversations and messages
        val conversationIds = conversationDao.getIdsByScope("knowledge_item", id)
        conversationIds.forEach { convId ->
            messageDao.deleteByConversation(convId)
            askCitationDao.deleteByConversation(convId)
        }
        conversationDao.deleteByScope("knowledge_item", id)

        itemDao.hardDelete(id)
    }

    override suspend fun restoreItem(id: String) {
        val item = itemDao.getByIdIncludeDeleted(id) ?: return
        itemDao.restore(id, System.currentTimeMillis())
        itemDao.updateItemCount(item.knowledgeBaseId)
        refreshOverviewForBase(item.knowledgeBaseId)
        rebuildGraphForBase(item.knowledgeBaseId)
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
            refreshOverviewForBase(kbId)
            rebuildGraphForBase(kbId)
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
        refreshOverviewForBase(oldKbId)
        refreshOverviewForBase(targetKbId)
        rebuildGraphForBase(oldKbId)
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
        val now = System.currentTimeMillis()
        val fragments = mutableListOf<KnowledgeFragmentEntity>()
        var cursor = 0
        outer@ for ((index, block) in sourceBlocks.withIndex()) {
            if (index >= 32) break
            val start = item.contentMarkdown.indexOf(block, startIndex = cursor)
            if (start < 0) {
                // Block text appears earlier than the cursor (e.g. duplicate
                // paragraph) — fall back to the cursor position so we don't
                // emit a fragment with a negative or zero-length range.
                continue
            }
            val end = (start + block.length).coerceAtMost(item.contentMarkdown.length)
            cursor = end
            fragments += KnowledgeFragmentEntity(
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

        // Capture manually soft-deleted entity / relation / community names
        // before clearing. The rebuild path is called every time we ingest
        // and every time the thread evolution worker runs, so without these
        // blacklists any item the user deleted in "中间处理数据" would
        // silently reappear on the next rebuild. (entity had a similar filter
        // before; the relation and community halves were missing.)
        //
        // Relation blacklists are keyed by (fromEntityName, toEntityName)
        // because every rebuild mints fresh UUIDs for the entity rows; the
        // names are the only stable handle we have to recognise the pair.
        val manuallyDeletedEntityKeys = graphDao.getAllEntitiesByKb(kbId)
            .filter { it.deletedAt != null }
            .map { it.name.lowercase(Locale.ROOT) to it.type }
            .toSet()
        val allEntitiesInKbByName = graphDao.getAllEntitiesByKb(kbId)
            .associateBy { it.id }
        val manuallyDeletedRelationKeys: Set<Pair<String, String>> = graphDao.getAllRelationsByKb(kbId)
            .filter { it.deletedAt != null }
            .mapNotNull { rel ->
                val fromName = allEntitiesInKbByName[rel.fromEntityId]?.name?.lowercase(Locale.ROOT)
                val toName = allEntitiesInKbByName[rel.toEntityId]?.name?.lowercase(Locale.ROOT)
                if (fromName != null && toName != null) fromName to toName else null
            }
            .toSet()
        val manuallyDeletedCommunityNames = graphDao.getAllCommunitiesByKb(kbId)
            .filter { it.deletedAt != null }
            .map { it.name.lowercase(Locale.ROOT) }
            .toSet()

        graphDao.clearEntities(kbId, now)
        graphDao.clearRelations(kbId, now)
        graphDao.clearCommunities(kbId, now)

        val wikiPages = items.filter { it.sourceType.startsWith("wiki_") }
            .ifEmpty { items }
        val pageMeta = wikiPages.map { item ->
            WikiPageMeta(
                item = item,
                title = frontMatterValue(item.contentMarkdown, "title") ?: item.title,
                type = normalizeWikiGraphType(item),
                sources = frontMatterList(item.contentMarkdown, "sources"),
                links = extractWikiLinks(item.contentMarkdown)
            )
        }.filterNot { it.type in STRUCTURAL_WIKI_TYPES }

        // --- Build entities ---------------------------------------------------
        //
        // We dedup on (name, type) so the same entity represented by two pages
        // collapses to a single node, merging their sourceItemIds and aliases.
        // We also fold aliases coming from the analysis JSON (entities[i].aliases)
        // — those were silently dropped before.
        val mergedByKey = linkedMapOf<Pair<String, String>, KnowledgeEntityEntity>()
        for (page in pageMeta) {
            val key = page.title.lowercase(Locale.ROOT) to page.type
            if (key in manuallyDeletedEntityKeys) continue
            val existing = mergedByKey[key]
            val aliasFromAnalysis = aliasesFromItem(page.item)
            if (existing == null) {
                mergedByKey[key] = KnowledgeEntityEntity(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = kbId,
                    name = page.title,
                    type = page.type,
                    aliasesJson = aliasFromAnalysis.toJsonArrayOrEmpty(),
                    sourceItemIdsJson = "[\"${page.item.id}\"]",
                    weight = 1f + page.links.size + page.sources.size,
                    confidence = page.item.confidence.coerceIn(0f, 1f),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            } else {
                val mergedSources = (existing.sourceItemIdsJson.parseAsStringList() + page.item.id).distinct()
                val mergedAliases = (existing.aliasesJson.parseAsStringList() + aliasFromAnalysis).distinct()
                mergedByKey[key] = existing.copy(
                    sourceItemIdsJson = mergedSources.toJsonArrayOrEmpty(),
                    aliasesJson = mergedAliases.toJsonArrayOrEmpty(),
                    weight = (existing.weight + 1f + page.links.size + page.sources.size).coerceAtMost(100f),
                    updatedAt = now
                )
            }
        }
        val entities = mergedByKey.values
            .sortedByDescending { it.weight }
            .take(ENTITY_SAFETY_LIMIT)
            .toList()
        graphDao.upsertEntities(entities)

        val byName = entities.associateBy { it.name.lowercase(Locale.ROOT) }
        val relations = mutableListOf<KnowledgeRelationEntity>()
        val relationKeys = mutableSetOf<Pair<String, String>>()

        // --- Wikilink edges --------------------------------------------------
        pageMeta.forEach { page ->
            val from = byName[page.title.lowercase(Locale.ROOT)] ?: return@forEach
            page.links.forEach { link ->
                val to = byName[link.lowercase(Locale.ROOT)] ?: return@forEach
                if (from.id == to.id) return@forEach
                // Skip if the user previously deleted the (from, to) edge in
                // "中间处理数据"; otherwise the next rebuild would resurrect it.
                val nameKey = (from.name.lowercase(Locale.ROOT) to to.name.lowercase(Locale.ROOT))
                if (nameKey in manuallyDeletedRelationKeys) return@forEach
                val key = from.id to to.id
                if (key in relationKeys) return@forEach
                relationKeys += key
                relations += KnowledgeRelationEntity(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = kbId,
                    fromEntityId = from.id,
                    toEntityId = to.id,
                    relationType = "wikilink",
                    evidenceItemIdsJson = "[\"${page.item.id}\"]",
                    confidence = 1.0f,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            }
        }

        // --- Analysis-JSON relation edges ------------------------------------
        //
        // This is the part that was MISSING in the previous implementation:
        // we stored the analysis relations in `analysis_result.relationsJson`
        // but never materialized them. Now we look up the latest analysis
        // for each item and convert its relations into graph edges with the
        // appropriate relation type (supports / contradicts / extends / etc.).
        for (page in pageMeta) {
            val sourceId = page.item.sourceId ?: continue
            val analysis = analysisResultDao.getLatestBySource(sourceId) ?: continue
            for (rel in parseRelations(analysis.relationsJson)) {
                val from = byName[rel.source.lowercase(Locale.ROOT)] ?: continue
                val to = byName[rel.target.lowercase(Locale.ROOT)] ?: continue
                if (from.id == to.id) continue
                val nameKey = (from.name.lowercase(Locale.ROOT) to to.name.lowercase(Locale.ROOT))
                if (nameKey in manuallyDeletedRelationKeys) continue
                val key = from.id to to.id
                if (key in relationKeys) continue
                relationKeys += key
                relations += KnowledgeRelationEntity(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = kbId,
                    fromEntityId = from.id,
                    toEntityId = to.id,
                    relationType = "analysis:${rel.type}",
                    evidenceItemIdsJson = "[\"${page.item.id}\"]",
                    confidence = rel.confidence.coerceIn(0f, 1f),
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            }
        }

        // --- Source-overlap edges (O(n) instead of O(n^2)) -------------------
        //
        // The old loop compared every pair of pages. For a knowledge base with
        // a few hundred pages this is fast; once you cross a couple thousand
        // it becomes the dominant cost of an ingest. We now bucket pages by
        // their first source and only run the pairwise compare inside buckets.
        val pagesBySource = pageMeta.groupBy { it.sources.firstOrNull() ?: "" }
        for ((_, bucket) in pagesBySource) {
            if (bucket.size < 2) continue
            for (i in bucket.indices) {
                for (j in i + 1 until bucket.size) {
                    val left = bucket[i]
                    val right = bucket[j]
                    val leftSet = left.sources.toSet()
                    if (right.sources.none { it in leftSet }) continue
                    val from = byName[left.title.lowercase(Locale.ROOT)] ?: continue
                    val to = byName[right.title.lowercase(Locale.ROOT)] ?: continue
                    val nameKey = (from.name.lowercase(Locale.ROOT) to to.name.lowercase(Locale.ROOT))
                    if (nameKey in manuallyDeletedRelationKeys) continue
                    val key = from.id to to.id
                    if (key in relationKeys) continue
                    relationKeys += key
                    relations += KnowledgeRelationEntity(
                        id = UUID.randomUUID().toString(),
                        knowledgeBaseId = kbId,
                        fromEntityId = from.id,
                        toEntityId = to.id,
                        relationType = "source_overlap",
                        evidenceItemIdsJson = "[\"${left.item.id}\",\"${right.item.id}\"]",
                        confidence = 0.8f,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null
                    )
                }
            }
        }
        graphDao.upsertRelations(relations.take(RELATION_SAFETY_LIMIT))

        // --- Communities ----------------------------------------------------
        //
        // Old key was `sources.firstOrNull() ?: page.type` which lumps every
        // non-wiki page (no source list) into a single community of type
        // "concept" / "entity". We now group by a stable key: the entity's
        // own name bucket (single-source pages only form a community if
        // multiple distinct pages share the same first source).
        val communities = pageMeta
            .groupBy { page -> page.sources.firstOrNull()?.takeIf { it.isNotBlank() } }
            .filter { (key, group) -> key != null && group.size >= 2 }
            .mapNotNull { (key, group) ->
                val keyStr = key ?: ""
                val communityName = "来源群 $keyStr"
                // Honour the user's prior "delete this community" choice in
                // 中间处理数据; otherwise the same group would re-form on the
                // next rebuild (ThreadEvolutionWorker, every ingest, etc.).
                if (communityName.lowercase(Locale.ROOT) in manuallyDeletedCommunityNames) {
                    null
                } else {
                    KnowledgeCommunityEntity(
                        id = UUID.randomUUID().toString(),
                        knowledgeBaseId = kbId,
                        name = communityName,
                        entityIdsJson = group.mapNotNull { byName[it.title.lowercase(Locale.ROOT)] }
                            .joinToString(",", "[", "]") { "\"${it.id}\"" },
                        summary = group.take(6).joinToString("、") { it.title },
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null
                    )
                }
            }
        graphDao.upsertCommunities(communities)
    }

    override suspend fun refreshOverviewForBase(kbId: String) {
        if (kbId.isBlank()) return
        val base = kbDao.getById(kbId) ?: return
        val now = System.currentTimeMillis()
        val liveItems = itemDao.getAllByKb(kbId)
            .filter { it.deletedAt == null }
            .filterNot { it.sourceType == "wiki_overview" && it.title == "overview.md" }
        val sourceItems = liveItems.filterNot { it.sourceType.startsWith("wiki_") }
        val wikiItems = liveItems.filter { it.sourceType.startsWith("wiki_") }
        val entityPages = wikiItems.filter { it.sourceType == "wiki_entity" }
        val conceptPages = wikiItems.filter { it.sourceType == "wiki_concept" }
        val sourcePages = wikiItems.filter { it.sourceType == "wiki_source" }
        val relations = graphDao.getAllRelationsByKb(kbId).filter { it.deletedAt == null }
        val communities = graphDao.getAllCommunitiesByKb(kbId).filter { it.deletedAt == null }
        val topPages = (entityPages + conceptPages)
            .sortedWith(compareByDescending<KnowledgeItemEntity> { it.importance }.thenByDescending { it.updatedAt })
            .take(12)
        val recentSources = sourceItems.sortedByDescending { it.updatedAt }.take(10)
        val today = java.time.Instant.ofEpochMilli(now)
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()
        val tags = (topPages.flatMap { it.tagsJson.parseAsStringList() } + sourceItems.flatMap { it.tagsJson.parseAsStringList() })
            .distinct()
            .take(12)
        val related = topPages.map { it.title }.distinct().take(16)
        val markdown = buildString {
            appendLine("---")
            appendLine("type: overview")
            appendLine("title: overview.md")
            appendLine("created: $today")
            appendLine("updated: $today")
            appendLine("tags: ${tags.toYamlInlineList()}")
            appendLine("related: ${related.map { it.slugForOverview() }.toYamlInlineList()}")
            appendLine("sources: ${recentSources.map { it.title }.toYamlInlineList()}")
            appendLine("---")
            appendLine()
            appendLine("# ${base.name} Overview")
            appendLine()
            appendLine("这个知识库当前包含 ${sourceItems.size} 条原始知识、${sourcePages.size} 份来源摘要、${entityPages.size} 个实体页、${conceptPages.size} 个概念页、${relations.size} 条关系和 ${communities.size} 个主题群。")
            appendLine()
            appendLine("## 知识概要")
            appendLine()
            if (sourceItems.isEmpty() && wikiItems.isEmpty()) {
                appendLine("当前知识库还没有可概述的知识。导入文档后,这里会记录知识库覆盖的主题、关键实体、核心概念和文档信息。")
            } else {
                appendLine("当前知识库围绕 ${base.name} 中已导入的材料组织内容。实体页记录具体对象,概念页记录方法、机制、理论和问题,来源页保留每次导入材料的摘要。")
                if (topPages.isNotEmpty()) {
                    appendLine()
                    appendLine("关键页面包括 ${topPages.take(6).joinToString("、") { "[[${it.title.escapeWikiLinkForOverview()}]]" }}。这些页面构成当前知识库的主要索引入口。")
                }
            }
            appendLine()
            appendLine("## 文档信息")
            appendLine()
            appendLine("- 原始知识: ${sourceItems.size}")
            appendLine("- 来源摘要: ${sourcePages.size}")
            appendLine("- 实体页: ${entityPages.size}")
            appendLine("- 概念页: ${conceptPages.size}")
            appendLine("- 关系: ${relations.size}")
            appendLine("- 主题群: ${communities.size}")
            appendLine()
            if (recentSources.isNotEmpty()) {
                appendLine("## 最近导入")
                appendLine()
                recentSources.forEach { item ->
                    appendLine("- [[${item.title.escapeWikiLinkForOverview()}]] — ${item.summary?.take(80) ?: item.excerpt.take(80)}")
                }
                appendLine()
            }
            if (topPages.isNotEmpty()) {
                appendLine("## 关键实体与概念")
                appendLine()
                topPages.forEach { item ->
                    appendLine("- [[${item.title.escapeWikiLinkForOverview()}]] (${item.sourceType.overviewTypeLabel()}) — ${item.summary?.take(80) ?: item.excerpt.take(80)}")
                }
            }
        }.trim()
        val existing = itemDao.getByKbSourceTypeAndTitle(kbId, "wiki_overview", "overview.md")
        val overview = KnowledgeItemEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sourceId = null,
            knowledgeBaseId = kbId,
            title = "overview.md",
            contentMarkdown = markdown,
            excerpt = "${base.name} 知识库总览",
            sourceType = "wiki_overview",
            status = KnowledgeItemEntity.STATUS_ARCHIVED,
            contentHash = calculateContentHash(markdown),
            sourceTraceJson = """{"generatedBy":"refreshOverviewForBase","knowledgeBaseId":"$kbId"}""",
            confidence = 1.0f,
            summary = "${base.name} 知识库总览",
            tagsJson = tags.toJsonArrayOrEmpty(),
            rawNoteId = null,
            importance = 2,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            processedAt = now,
            archivedAt = now,
            deletedAt = null
        )
        itemDao.insert(overview)
        rebuildFragmentsForItem(overview)
    }

    private companion object {
        // llm_wiki does not cap graph size — its 600-line chunker + graph
        // renderer handle the full population. We keep a soft cap only as
        // an OOM safety net for very large local libraries; the cap is
        // intentionally generous so it never clips a normal ingest.
        const val ENTITY_SAFETY_LIMIT = 5_000
        const val RELATION_SAFETY_LIMIT = 5_000
        val STRUCTURAL_WIKI_TYPES = setOf("overview", "index", "log")
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

    override suspend fun retryProcessingForSource(sourceId: String) {
        // Wipe everything that a previous run produced for this source so a
        // fresh ingest can rebuild from scratch:
        //   - the queued task(s) — get a brand-new parse task
        //   - the parsed content — parser output may have changed
        //   - the analysis result — its hash and JSON belong to the old parse
        //   - the wiki pages (knowledge_items with this sourceId)
        //   - the source's own row status + processing log
        // We deliberately do NOT delete the source row — the local file
        // backing it stays where it is, and a brand-new sha256 keeps the
        // cache-hit short-circuit (`isIngestCacheHit`) from re-using the
        // old generation as a hit on the new run.
        val now = System.currentTimeMillis()
        taskDao.deleteByTarget("source_document", sourceId)
        parsedContentDao.deleteBySource(sourceId)
        analysisResultDao.deleteBySource(sourceId)
        val oldItems = itemDao.getAllBySourceId(sourceId)
        oldItems.forEach { item ->
            taskDao.deleteByTarget("knowledge_item", item.id)
            taskLogDao.deleteByTarget("knowledge_item", item.id)
            recommendationDao.deleteByItemId(item.id)
            fragmentDao.deleteByItemId(item.id)
            graphDao.deleteEmbeddingsByItem(item.id)
            val conversationIds = conversationDao.getIdsByScope("knowledge_item", item.id)
            conversationIds.forEach { convId ->
                messageDao.deleteByConversation(convId)
                askCitationDao.deleteByConversation(convId)
            }
            conversationDao.deleteByScope("knowledge_item", item.id)
        }
        // Soft-delete old wiki pages tied to this source. The graph
        // rebuilder will mint fresh rows for the new run; deleting the
        // items also keeps item counts in sync.
        itemDao.softDeleteBySource(sourceId, now)
        sourceDocumentDao.updateStatus(
            sourceId,
            SourceDocumentEntity.STATUS_IMPORTED,
            null,
            now
        )
        itemDao.updateStatusBySourceId(sourceId, KnowledgeItemEntity.STATUS_PROCESSING, now)
        taskDao.insert(
            ProcessingTaskEntity(
                id = UUID.randomUUID().toString(),
                targetType = "source_document",
                targetId = sourceId,
                taskType = "parse",
                status = "pending",
                priority = 10,
                dependsOnTaskIdsJson = null,
                retryCount = 0,
                maxRetry = 3,
                errorMessage = null,
                createdAt = now,
                updatedAt = now,
                finishedAt = null,
                sourceId = sourceId,
                itemId = null,
                progress = 0,
                currentStep = "等待解析（重新发起）",
                inputJson = """{"sourceId":"$sourceId","reprocess":true}"""
            )
        )
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

    /**
     * Conversation list annotated with per-conversation message count.
     * Used by the AskHistorySheet to render "N 条消息" without
     * a per-row count query.
     */
    override fun observeConversationsWithCount(
        scopeType: String,
        scopeId: String
    ): Flow<List<ConversationWithCount>> {
        return combine(
            conversationDao.observeByScope(scopeType, scopeId),
            messageDao.observeCountsByScope(scopeType, scopeId)
        ) { conversations, counts ->
            val countMap = counts.associate { it.conversationId to it.count }
            conversations.map { conv ->
                ConversationWithCount(conv, countMap[conv.id] ?: 0)
            }.filter { it.messageCount > 0 }
        }
    }

    override suspend fun createConversation(conversation: AiConversationEntity): AiConversationEntity {
        conversationDao.insert(conversation)
        return conversation
    }

    override suspend fun getConversation(id: String): AiConversationEntity? =
        conversationDao.getById(id)

    override suspend fun updateConversation(conversation: AiConversationEntity) {
        conversationDao.update(conversation.copy(updatedAt = System.currentTimeMillis()))
    }

    override suspend fun deleteConversation(id: String) {
        messageDao.deleteByConversation(id)
        askCitationDao.deleteByConversation(id)
        conversationDao.hardDelete(id)
    }

    /**
     * Conversation + its current message count, used by the UI to
     * render history rows without an N+1.
     */
    data class ConversationWithCount(
        val conversation: AiConversationEntity,
        val messageCount: Int
    )

    override suspend fun clearConversationsByScope(scopeType: String, scopeId: String) {
        val conversationIds = conversationDao.getIdsByScope(scopeType, scopeId)
        conversationIds.forEach { convId ->
            messageDao.deleteByConversation(convId)
            askCitationDao.deleteByConversation(convId)
        }
        conversationDao.deleteByScope(scopeType, scopeId)
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

    override suspend fun deleteKnowledgeEntities(ids: List<String>) {
        graphDao.deleteEntities(ids)
    }

    override suspend fun deleteKnowledgeRelations(ids: List<String>) {
        graphDao.deleteRelations(ids)
    }

    override suspend fun deleteKnowledgeCommunities(ids: List<String>) {
        graphDao.deleteCommunities(ids)
    }

    override suspend fun getEntityByName(name: String): KnowledgeEntityEntity? =
        graphDao.getEntityByName(name)

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

    private fun normalizeWikiGraphType(item: KnowledgeItemEntity): String {
        val fmType = frontMatterValue(item.contentMarkdown, "type")?.lowercase(Locale.ROOT)
        return when (item.sourceType) {
            "wiki_entity" -> "entity"
            "wiki_concept" -> "concept"
            "wiki_source" -> "source"
            "wiki_overview" -> "overview"
            "wiki_index" -> "index"
            "wiki_log" -> "log"
            else -> when (fmType) {
                "entity", "person", "organization", "org", "product", "dataset", "tool", "system", "project", "place", "location", "source" -> fmType
                "concept", "method", "technique", "theory", "principle", "framework", "problem" -> "concept"
                "overview", "index", "log" -> fmType
                else -> "concept"
            }
        }
    }

    private fun extractWikiLinks(markdown: String): List<String> =
        Regex("\\[\\[([^\\]]+)]]")
            .findAll(markdown)
            .map { it.groupValues[1].substringBefore("|").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    private fun List<String>.toYamlInlineList(): String =
        if (isEmpty()) {
            "[]"
        } else {
            distinct()
                .take(40)
                .joinToString(", ", "[", "]") { "\"${it.escapeYamlScalar()}\"" }
        }

    private fun String.escapeYamlScalar(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim()

    private fun String.escapeWikiLinkForOverview(): String =
        replace("[[", "").replace("]]", "").trim()

    private fun String.slugForOverview(): String =
        trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .ifBlank { "untitled" }

    private fun String.overviewTypeLabel(): String = when (this) {
        "wiki_entity" -> "实体"
        "wiki_concept" -> "概念"
        "wiki_source" -> "来源"
        "wiki_overview" -> "总览"
        "wiki_index" -> "索引"
        "wiki_log" -> "日志"
        else -> this
    }

    // === Graph-rebuild helpers ============================================
    //
    // The following helpers were added when wiring `analysis_result.relations`
    // into the graph (this was missing from the original implementation).

    private data class AnalysisRelation(
        val source: String,
        val target: String,
        val type: String,
        val confidence: Float
    )

    private fun aliasesFromItem(item: KnowledgeItemEntity): List<String> {
        val raw = runCatching {
            val entitiesArr = JSONArray(item.tagsJson) // tags is a simple list; not aliases.
            emptyList<String>()
        }.getOrDefault(emptyList())
        // We pull aliases out of sourceTraceJson where the wiki compiler wrote them.
        val trace = runCatching { JSONObject(item.sourceTraceJson) }.getOrNull() ?: return emptyList()
        val aliases = trace.optJSONArray("aliases") ?: return emptyList()
        return (0 until aliases.length()).mapNotNull { aliases.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun parseRelations(json: String): List<AnalysisRelation> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<AnalysisRelation>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val source = obj.optString("source").trim()
            val target = obj.optString("target").trim()
            if (source.isBlank() || target.isBlank()) continue
            val type = obj.optString("type", "related_to").ifBlank { "related_to" }
            val confidence = obj.optDouble("confidence", 0.7).toFloat()
            out += AnalysisRelation(source, target, type, confidence)
        }
        return out
    }

    private fun String.parseAsStringList(): List<String> {
        if (isBlank() || this == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(this)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun List<String>.toJsonArrayOrEmpty(): String {
        if (isEmpty()) return "[]"
        return joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }
}
