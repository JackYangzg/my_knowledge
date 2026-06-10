package com.my.knowledge.data.repository

import androidx.room.withTransaction
import com.my.knowledge.data.db.AppDatabase
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
import com.my.knowledge.data.ai.AiPromptTemplates
import com.my.knowledge.data.ingest.EntityName
import com.my.knowledge.data.ingest.WikiPageCompiler
import com.my.knowledge.data.ingest.WikiPageDraft
import com.my.knowledge.domain.model.isKnowledgeConceptType
import com.my.knowledge.domain.repository.BackfillResult
import com.my.knowledge.domain.repository.KnowledgeRepository
import com.my.knowledge.domain.repository.ProfileStats
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import com.my.knowledge.data.util.Sha256
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.*

class KnowledgeRepositoryImpl(
    private val db: AppDatabase,
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

    override suspend fun getItemsByIds(ids: List<String>): List<KnowledgeItemEntity> =
        if (ids.isEmpty()) emptyList() else itemDao.getByIds(ids)

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
        val now = System.currentTimeMillis()
        if (softDelete) {
            itemDao.softDelete(id, now)
            val touchedIds = item.sourceId?.let { sourceId ->
                cleanupIntermediateDataForSource(sourceId, now, softDeleteGeneratedItems = true)
            } ?: emptySet()
            // PERF-5 + CASCADE-1: scoped rebuild fed with both the
            // original item and the wiki_* pages that just got
            // soft-deleted. The graph rows' `sourceItemIdsJson`
            // references the wiki pages (not the original note), so
            // without the wiki page IDs in the input set
            // `rebuildGraphForBaseAffected` cannot find them and
            // would leak entity/relation/community rows that
            // IntermediateDataViewModel still observes.
            itemDao.updateItemCount(item.knowledgeBaseId)
            refreshOverviewForBase(item.knowledgeBaseId)
            rebuildGraphForBaseAffected(item.knowledgeBaseId, touchedIds + id)
        } else {
            permanentDeleteItem(id)
            itemDao.updateItemCount(item.knowledgeBaseId)
            refreshOverviewForBase(item.knowledgeBaseId)
            // PERF-5: scoped rebuild — the original item is now
            // hard-deleted, so affectedItems is empty; the rebuild
            // only runs the graph cleanup pass against the original
            // item's id. Wiki pages survive the hard delete by
            // design, so the graph rows that reference them stay
            // valid and don't need clearing.
            rebuildGraphForBaseAffected(item.knowledgeBaseId, setOf(id))
        }
    }

    override suspend fun permanentDeleteItem(id: String) {
        val item = itemDao.getByIdIncludeDeleted(id)
        taskDao.deleteByTarget("knowledge_item", id)
        recommendationDao.deleteByItemId(id)
        fragmentDao.deleteByItemId(id)
        taskLogDao.deleteByTarget("knowledge_item", id)
        item?.sourceId?.let { sourceId ->
            cleanupIntermediateDataForSource(
                sourceId = sourceId,
                now = System.currentTimeMillis(),
                softDeleteGeneratedItems = false
            )
        }
        
        // Delete associated AI conversations and messages
        val conversationIds = conversationDao.getIdsByScope("knowledge_item", id)
        conversationIds.forEach { convId ->
            messageDao.deleteByConversation(convId)
            askCitationDao.deleteByConversation(convId)
        }
        conversationDao.deleteByScope("knowledge_item", id)

        itemDao.hardDelete(id)
    }

    private suspend fun cleanupIntermediateDataForSource(
        sourceId: String,
        now: Long,
        softDeleteGeneratedItems: Boolean
    ): Set<String> {
        taskDao.deleteBySource(sourceId)
        taskLogDao.deleteByTarget("source_document", sourceId)
        reviewItemDao.skipBySource(sourceId, now)
        fragmentDao.deleteBySource(sourceId)
        parsedContentDao.deleteBySource(sourceId)
        analysisResultDao.deleteBySource(sourceId)

        val generatedItems = itemDao.getAllBySourceId(sourceId)
        val touchedItemIds = generatedItems.map { it.id }.toMutableSet()
        generatedItems.forEach { generated ->
            recommendationDao.deleteByItemId(generated.id)
            taskDao.deleteByTarget("knowledge_item", generated.id)
            taskLogDao.deleteByTarget("knowledge_item", generated.id)
            fragmentDao.deleteByItemId(generated.id)
            graphDao.deleteEmbeddingsByItem(generated.id)
            val conversationIds = conversationDao.getIdsByScope("knowledge_item", generated.id)
            conversationIds.forEach { conversationId ->
                messageDao.deleteByConversation(conversationId)
                askCitationDao.deleteByConversation(conversationId)
            }
            conversationDao.deleteByScope("knowledge_item", generated.id)
            if (softDeleteGeneratedItems) {
                itemDao.softDelete(generated.id, now)
            }
        }
        sourceDocumentDao.markDeleted(sourceId, now)
        return touchedItemIds
    }

    override suspend fun restoreItem(id: String) {
        val item = itemDao.getByIdIncludeDeleted(id) ?: return
        itemDao.restore(id, System.currentTimeMillis())
        itemDao.updateItemCount(item.knowledgeBaseId)
        refreshOverviewForBase(item.knowledgeBaseId)
        // PERF-5: scoped rebuild.
        rebuildGraphForBaseAffected(item.knowledgeBaseId, setOf(id))
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
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val sourceIdsProcessed = mutableSetOf<String>()
        // KB → set of itemIds we are about to hard-delete in that KB.
        // The scoped graph rebuild needs both the original item ids
        // and the wiki_* page ids that share each item's sourceId,
        // because the graph rows' `sourceItemIdsJson` references the
        // wiki pages, not the user-visible note. Without the wiki
        // page ids the rebuild's affected-detection phase finds
        // nothing and leaks the dangling graph rows.
        val kbAffectedIds = mutableMapOf<String, MutableSet<String>>()
        val itemsToHardDelete = mutableListOf<String>()

        ids.forEach { id ->
            val item = itemDao.getByIdIncludeDeleted(id)
            taskDao.deleteByTarget("knowledge_item", id)
            recommendationDao.deleteByItemId(id)
            fragmentDao.deleteByItemId(id)
            taskLogDao.deleteByTarget("knowledge_item", id)
            item?.sourceId?.let { sourceId ->
                if (sourceIdsProcessed.add(sourceId)) {
                    val touched = cleanupIntermediateDataForSource(
                        sourceId = sourceId,
                        now = now,
                        softDeleteGeneratedItems = false
                    )
                    if (item.knowledgeBaseId.isNotBlank()) {
                        kbAffectedIds.getOrPut(item.knowledgeBaseId) { mutableSetOf() }
                            .addAll(touched)
                    }
                }
            }
            // AI conversation / message / ask_citation cleanup is
            // per-knowledge-item; the singular `permanentDeleteItem`
            // already does this and we mirror that here so the
            // batch path doesn't leak Ask transcripts the way the
            // old stub did.
            val conversationIds = conversationDao.getIdsByScope("knowledge_item", id)
            conversationIds.forEach { convId ->
                messageDao.deleteByConversation(convId)
                askCitationDao.deleteByConversation(convId)
            }
            conversationDao.deleteByScope("knowledge_item", id)

            if (item != null && item.knowledgeBaseId.isNotBlank()) {
                kbAffectedIds.getOrPut(item.knowledgeBaseId) { mutableSetOf() }.add(id)
            }
            itemsToHardDelete.add(id)
        }
        itemDao.hardDeleteItems(itemsToHardDelete)

        // PERF-5 + CASCADE-1: scoped rebuild for every KB we touched
        // so the affected graph rows (entity / relation / community)
        // get cleared. With the fix to `rebuildGraphForBaseAffected`,
        // the rebuild handles the "no surviving items" case by
        // running the affected-detection pass against the input ids
        // and returning before the re-derivation step.
        kbAffectedIds.forEach { (kbId, kbItemIds) ->
            itemDao.updateItemCount(kbId)
            refreshOverviewForBase(kbId)
            rebuildGraphForBaseAffected(kbId, kbItemIds)
        }
    }

    override suspend fun moveItemToBase(itemId: String, targetKbId: String) {
        val item = itemDao.getById(itemId) ?: run {
            android.util.Log.w("KnowledgeRepo", "moveItemToBase: item $itemId not found (deleted?)")
            return
        }
        if (item.knowledgeBaseId == targetKbId) return // nothing to do

        val oldKbId = item.knowledgeBaseId
        val targetBase = kbDao.getById(targetKbId)
        val now = System.currentTimeMillis()

        // CQ-6: removed the decorative try/catch — the exception now
        // propagates to the caller's `viewModelScope` and is caught
        // there (the AskViewModel / KnowledgeEditorViewModel already
        // show a friendly error toast). The repo-layer `Log.e` was
        // a duplicate of what the top-level crash handler would log
        // anyway and added no information beyond the stack trace
        // already in the throwable.

        // Step 1: move the item itself
        itemDao.moveToBase(itemId, targetKbId, now)
        if (targetBase?.type != "unfiled") {
            itemDao.update(
                item.copy(
                    knowledgeBaseId = targetKbId,
                    status = KnowledgeItemEntity.STATUS_ARCHIVED,
                    updatedAt = now
                )
            )
        }

        // Step 2: migrate associated data so they stay in the same KB
        // Fragments follow the item
        fragmentDao.updateKbIdByItem(itemId, targetKbId)
        // Embeddings follow the fragments
        graphDao.updateEmbeddingsKbByItem(itemId, targetKbId)
        // Entities that are exclusively backed by this item move with it;
        // "exclusive" = no other item in source KB still references the
        // entity, so changing its knowledgeBaseId can't strand any
        // source-side reference. Done as a single SQL UPDATE.
        graphDao.moveExclusiveEntitiesByItem(itemId, oldKbId, targetKbId, now)
        // Entities that are SHARED with other items in source cannot be
        // yanked out of source (would break the source-side references).
        // For those, COPY the entity to target so the moved item keeps
        // its link, and trim sourceItemIdsJson on the source copy to
        // drop the moved item. Source still owns the entity for its
        // remaining items; the moved item owns a fresh copy in target.
        copySharedEntitiesToTarget(itemId, oldKbId, targetKbId, now)
        // Relations whose both endpoints landed in the target KB follow
        graphDao.moveRelationsToKbByEndpoints(oldKbId, targetKbId, now)
        // Communities whose every member is now in the target KB follow
        graphDao.moveCommunitiesToKbByEntities(oldKbId, targetKbId, now)

        // Step 3: update KB item counts and refresh overview
        itemDao.updateItemCount(oldKbId)
        itemDao.updateItemCount(targetKbId)
        refreshOverviewForBase(oldKbId)
        refreshOverviewForBase(targetKbId)

        // Step 4: rebuild both KB graphs — the source loses orphaned data,
        // the target gains the newly migrated records. PERF-5: scoped
        // rebuild — the moved item is the only thing that changed.
        rebuildGraphForBaseAffected(oldKbId, setOf(itemId))
        if (targetBase?.type != "unfiled") rebuildGraphForBaseAffected(targetKbId, setOf(itemId))
    }

    /**
     * Move-handling for entities/concepts that are SHARED between the
     * source KB and the moved item.
     *
     * `moveExclusiveEntitiesByItem` already handled the easy case (the
     * entity's only reference was the moved item → just change its
     * `knowledgeBaseId`). After it runs, the entities still left in
     * source that reference the moved item are the ones that have
     * additional source-side consumers.
     *
     * For each such entity, we cannot delete it from source (the
     * remaining source items would lose the link) and we cannot leave
     * it solely in source (the moved item, now in target, would lose
     * the link). The fix is to fork: the source keeps the original
     * with `sourceItemIdsJson` trimmed to drop the moved item, and the
     * target receives a fresh copy whose only reference is the moved
     * item. The follow-up `rebuildGraphForBaseAffected(targetKbId)`
     * re-derives relations/communities against the new copy from the
     * item's content.
     */
    private suspend fun copySharedEntitiesToTarget(
        itemId: String,
        oldKbId: String,
        newKbId: String,
        now: Long
    ) {
        // sourceItemIdsJson is a JSON array of item-id strings,
        // e.g. ["abc","def"]. We just need the set membership check.
        val refsRegex = Regex("\"([^\"]+)\"")
        fun refsOf(json: String): Set<String> =
            refsRegex.findAll(json).map { it.groupValues[1] }.toSet()

        val stillInSource = graphDao.getAllEntitiesByKb(oldKbId)
            .filter { itemId in refsOf(it.sourceItemIdsJson) }
        if (stillInSource.isEmpty()) return

        // Item IDs that are still in source AFTER the move of itemId
        // (itemId itself is no longer in oldKbId at this point).
        val sourceItemIds = itemDao.getAllByKb(oldKbId).map { it.id }.toSet()
        if (sourceItemIds.isEmpty()) return

        val toUpsert = mutableListOf<KnowledgeEntityEntity>()
        for (entity in stillInSource) {
            val refs = refsOf(entity.sourceItemIdsJson)
            val refsStillInSource = refs.filter { it != itemId && it in sourceItemIds }
            if (refsStillInSource.isEmpty()) {
                // The exclusive case should have been caught by
                // moveExclusiveEntitiesByItem. If we still see zero
                // refs here (e.g. all other refs are soft-deleted),
                // skip the copy — the source entity is effectively
                // orphan and the rebuild will tidy it up.
                continue
            }
            // 1) Copy to target: same name/aliases/weight/confidence,
            //    new ID, new KB, sourceItemIdsJson = [moved item].
            toUpsert.add(
                entity.copy(
                    id = UUID.randomUUID().toString(),
                    knowledgeBaseId = newKbId,
                    sourceItemIdsJson = listOf(itemId).toJsonArrayOrEmpty(),
                    createdAt = now,
                    updatedAt = now
                )
            )
            // 2) Trim source: drop the moved item from the original's
            //    sourceItemIdsJson. Source still references it from
            //    `refsStillInSource`.
            toUpsert.add(
                entity.copy(
                    sourceItemIdsJson = refsStillInSource.toJsonArrayOrEmpty(),
                    updatedAt = now
                )
            )
        }
        if (toUpsert.isNotEmpty()) {
            graphDao.upsertEntities(toUpsert)
        }
    }

    // === Unfiled operations ===
    override fun observeUnfiledItems(limit: Int, offset: Int): Flow<List<KnowledgeItemEntity>> =
        itemDao.observeUnfiledItems(limit, offset)

    override fun observeUnfiledItemCount(): Flow<Int> = itemDao.observeUnfiledItemCount()

    override suspend fun getUnfiledBase(): KnowledgeBaseEntity? = kbDao.getByType("unfiled")

    // === Content Hash ===
    override fun calculateContentHash(content: String): String = Sha256.hex(content)

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
        // Wrap the full clear-then-rebuild in a single Room
        // transaction. The previous implementation ran the soft-delete
        // (graphDao.clear*) and the re-derive (graphDao.upsert*) as
        // separate implicit transactions: if anything between them
        // threw (e.g. an analysis-JSON relation parser crash), the KB
        // was left with all entities soft-deleted and no live rows,
        // and the user observed "existing entities vanished" until the
        // next successful rebuild landed. withTransaction rolls the
        // whole rebuild back on any throw, so a partial failure
        // preserves the previous snapshot.
        db.withTransaction {
            rebuildGraphForBaseInternal(kbId)
        }
    }

    private suspend fun rebuildGraphForBaseInternal(kbId: String) {
        // 优先走 wiki-only 查询(刚加的 getAllWikiByKb)——脉络/图谱重建
        // 只需要 wiki 页面,KB 笔记量很大时,这一步把内存 + Room I/O 减少
        // 一个数量级。原 `getAllByKb` 仅在没有任何 wiki 页时作为兜底
        // (比如老 KB 完全没经过 ingest pipeline)。
        val allItems = itemDao.getAllByKb(kbId).filter { it.deletedAt == null }
        val wikiOnly = itemDao.getAllWikiByKb(kbId)
        val items: List<com.my.knowledge.data.db.entity.KnowledgeItemEntity> =
            if (wikiOnly.isNotEmpty()) wikiOnly else allItems
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
        // PERF-12: getAllEntitiesByKb was called twice (once for the
        // manual-delete filter, once for the by-id map). Both lookups
        // consume the same row set (the DAO returns all rows, no
        // filter on `deletedAt`) — derive both views from a single
        // SELECT so we don't pay 2x table-scan cost on a 1K+ entity
        // graph rebuild.
        //
        // Whitespace-normalized dedup: old code used `name.lowercase()`
        // only, which let "Foo Bar" and " Foo Bar" coexist as TWO graph
        // nodes even though the wiki pages had already been merged.
        // `EntityName.dedupKey` collapses internal / leading / trailing
        // whitespace and Unicode whitespace (NBSP, U+3000) so all
        // variants of the same logical name share one graph node.
        val allEntitiesInKb = graphDao.getAllEntitiesByKb(kbId)
        val manuallyDeletedEntityKeys = allEntitiesInKb
            .filter { it.deletedAt != null }
            .map { com.my.knowledge.data.ingest.EntityName.dedupKey(it.name) to it.type }
            .toSet()
        val allEntitiesInKbByName = allEntitiesInKb.associateBy { it.id }
        val manuallyDeletedRelationKeys: Set<Pair<String, String>> = graphDao.getAllRelationsByKb(kbId)
            .filter { it.deletedAt != null }
            .mapNotNull { rel ->
                val fromName = allEntitiesInKbByName[rel.fromEntityId]?.name?.let { com.my.knowledge.data.ingest.EntityName.dedupKey(it) }
                val toName = allEntitiesInKbByName[rel.toEntityId]?.name?.let { com.my.knowledge.data.ingest.EntityName.dedupKey(it) }
                if (fromName != null && toName != null) fromName to toName else null
            }
            .toSet()
        val manuallyDeletedCommunityNames = graphDao.getAllCommunitiesByKb(kbId)
            .filter { it.deletedAt != null }
            .map { com.my.knowledge.data.ingest.EntityName.dedupKey(it.name) }
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
        //
        // Key uses `EntityName.dedupKey` (whitespace-normalized lowercase)
        // so "Foo Bar" and " Foo Bar" share one graph node. See the note
        // on `manuallyDeletedEntityKeys` above for the rationale.
        val mergedByKey = linkedMapOf<Pair<String, String>, KnowledgeEntityEntity>()
        for (page in pageMeta) {
            val key = com.my.knowledge.data.ingest.EntityName.dedupKey(page.title) to page.type
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

        val byName = entities.associateBy { com.my.knowledge.data.ingest.EntityName.dedupKey(it.name) }
        val relations = mutableListOf<KnowledgeRelationEntity>()
        val relationKeys = mutableSetOf<Pair<String, String>>()

        // --- Wikilink edges --------------------------------------------------
        pageMeta.forEach { page ->
            val from = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(page.title)] ?: return@forEach
            page.links.forEach { link ->
                val to = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(link)] ?: return@forEach
                if (from.id == to.id) return@forEach
                // Skip if the user previously deleted the (from, to) edge in
                // "中间处理数据"; otherwise the next rebuild would resurrect it.
                val nameKey = (com.my.knowledge.data.ingest.EntityName.dedupKey(from.name) to com.my.knowledge.data.ingest.EntityName.dedupKey(to.name))
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
                val from = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(rel.source)] ?: continue
                val to = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(rel.target)] ?: continue
                if (from.id == to.id) continue
                val nameKey = (com.my.knowledge.data.ingest.EntityName.dedupKey(from.name) to com.my.knowledge.data.ingest.EntityName.dedupKey(to.name))
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
                    val from = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(left.title)] ?: continue
                    val to = byName[com.my.knowledge.data.ingest.EntityName.dedupKey(right.title)] ?: continue
                    val nameKey = (com.my.knowledge.data.ingest.EntityName.dedupKey(from.name) to com.my.knowledge.data.ingest.EntityName.dedupKey(to.name))
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
                if (com.my.knowledge.data.ingest.EntityName.dedupKey(communityName) in manuallyDeletedCommunityNames) {
                    null
                } else {
                    KnowledgeCommunityEntity(
                        id = UUID.randomUUID().toString(),
                        knowledgeBaseId = kbId,
                        name = communityName,
                        entityIdsJson = group.mapNotNull { byName[com.my.knowledge.data.ingest.EntityName.dedupKey(it.title)] }
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

    /**
     * PERF-5: scoped variant of [rebuildGraphForBase] for the
     * common "one item changed" case. The full rebuild is O(KB
     * pages × KB pages) for the source-overlap edge pass and
     * O(KB pages) for every other stage — a single delete in a
     * 5K-page KB would otherwise trigger a ~5K² comparison.
     *
     * Strategy:
     *  1. Load the current graph rows for the KB.
     *  2. Find the rows whose `sourceItemIdsJson` /
     *     `evidenceItemIdsJson` / `entityIdsJson` overlap with
     *     the affected item IDs. For relations / communities we
     *     also bubble out through dangling endpoints (an entity
     *     going away kills every relation that touched it and
     *     every community that referenced it).
     *  3. Hard-delete those rows. The full rebuild re-applies
     *     the `manuallyDeletedEntityKeys` / `manuallyDeletedRelationKeys`
     *     / `manuallyDeletedCommunityNames` filter, so user-curated
     *     soft-deletes are still honoured.
     *  4. Re-materialise only from the affected pages, merging
     *     with the survivors that were not touched. The merge
     *     step is the same `mergedByKey` / `relationKeys` logic
     *     as the full rebuild, just seeded with survivors.
     */
    override suspend fun rebuildGraphForBaseAffected(kbId: String, itemIds: Set<String>) {
        if (kbId.isBlank() || itemIds.isEmpty()) return
        val now = System.currentTimeMillis()
        val itemIdSet = itemIds

        // Materialise the affected pages. We don't filter on
        // `knowledgeBaseId` here: in the move path the item has
        // already been reassigned to the target KB by the time
        // we run, but the source KB's graph rows still point at
        // the old itemId and must be re-derived.
        //
        // Do NOT early-return when the items are all soft-deleted.
        // The soft-delete path is the common caller (the user just
        // tapped Delete on a knowledge entry), and the graph rows
        // that reference the now-tombstoned items still need to be
        // cleared before IntermediateDataViewModel observes them as
        // "active". The re-derivation phase below does need at
        // least one surviving item to feed into the rebuild, so we
        // keep the affectedItems list as the re-derivation's input
        // and gate on it AFTER the graph cleanup.
        val affectedItems = itemDao.getAllByIds(itemIdSet.toList())
            .filter { it.deletedAt == null }

        val allEntities = graphDao.getAllEntitiesByKb(kbId)
        val allRelations = graphDao.getAllRelationsByKb(kbId)
        val allCommunities = graphDao.getAllCommunitiesByKb(kbId)

        val entitiesById = allEntities.associateBy { it.id }
        fun entityIdsIn(json: String): Set<String> = json.parseAsStringList().toSet()

        // Find graph rows that touch the affected items directly,
        // then bubble out: a relation whose endpoint is being
        // removed must go too, and a community that references a
        // removed entity must go too.
        var affectedEntityIds = allEntities
            .filter { entityIdsIn(it.sourceItemIdsJson).any { id -> id in itemIdSet } }
            .map { it.id }
            .toMutableSet()
        var affectedRelationIds = allRelations
            .filter { rel ->
                entityIdsIn(rel.evidenceItemIdsJson).any { id -> id in itemIdSet } ||
                    rel.fromEntityId in affectedEntityIds ||
                    rel.toEntityId in affectedEntityIds
            }
            .map { it.id }
            .toMutableSet()
        var affectedCommunityIds = allCommunities
            .filter { community ->
                entityIdsIn(community.entityIdsJson).any { id -> id in affectedEntityIds } ||
                    community.entityIdsJson.parseAsStringList()
                        .any { id -> id in affectedRelationIds }
            }
            .map { it.id }
            .toMutableSet()

        if (affectedEntityIds.isNotEmpty()) graphDao.deleteEntities(affectedEntityIds.toList(), now)
        if (affectedRelationIds.isNotEmpty()) graphDao.deleteRelations(affectedRelationIds.toList(), now)
        if (affectedCommunityIds.isNotEmpty()) graphDao.deleteCommunities(affectedCommunityIds.toList(), now)

        // If we cleared some graph rows AND there are no surviving
        // items in this batch, the soft-delete path has finished
        // its job: the items that contributed those graph rows are
        // gone, and we have nothing alive to feed into a
        // re-derivation. The move path always passes an alive item
        // (it has just been reassigned to a different KB), so this
        // branch only fires for the soft-delete path.
        if (affectedItems.isEmpty()) return

        val survivingEntities = allEntities.filterNot { it.id in affectedEntityIds }
        val survivingRelations = allRelations.filterNot { it.id in affectedRelationIds }
        val survivingCommunities = allCommunities.filterNot { it.id in affectedCommunityIds }

        // Same manually-deleted filters as the full rebuild. The
        // user might have soft-deleted an entity / relation /
        // community in "中间处理数据" while its backing item
        // was still in the KB; the scoped rebuild must not
        // resurrect it.
        val manuallyDeletedEntityKeys = allEntities
            .filter { it.deletedAt != null }
            .map { EntityName.dedupKey(it.name) to it.type }
            .toSet()
        val entityIndexForManualKey = (survivingEntities + allEntities.filter { it.id in affectedEntityIds })
            .associateBy { it.id }
        val manuallyDeletedRelationKeys: Set<Pair<String, String>> = allRelations
            .filter { it.deletedAt != null }
            .mapNotNull { rel ->
                val fromName = entityIndexForManualKey[rel.fromEntityId]?.name?.let { EntityName.dedupKey(it) }
                val toName = entityIndexForManualKey[rel.toEntityId]?.name?.let { EntityName.dedupKey(it) }
                if (fromName != null && toName != null) fromName to toName else null
            }
            .toSet()
        val manuallyDeletedCommunityNames = allCommunities
            .filter { it.deletedAt != null }
            .map { EntityName.dedupKey(it.name) }
            .toSet()

        val pageMeta = affectedItems.map { item ->
            WikiPageMeta(
                item = item,
                title = frontMatterValue(item.contentMarkdown, "title") ?: item.title,
                type = normalizeWikiGraphType(item),
                sources = frontMatterList(item.contentMarkdown, "sources"),
                links = extractWikiLinks(item.contentMarkdown)
            )
        }.filterNot { it.type in STRUCTURAL_WIKI_TYPES }

        // --- Build entities -------------------------------------------------
        val mergedByKey = linkedMapOf<Pair<String, String>, KnowledgeEntityEntity>()
        for (entity in survivingEntities) {
            val key = EntityName.dedupKey(entity.name) to entity.type
            mergedByKey[key] = entity
        }
        for (page in pageMeta) {
            val key = EntityName.dedupKey(page.title) to page.type
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

        val byName = entities.associateBy { EntityName.dedupKey(it.name) }
        val relations = mutableListOf<KnowledgeRelationEntity>()
        val relationKeys = mutableSetOf<Pair<String, String>>()
        // Seed with survivors so we don't re-derive or duplicate
        // edges that don't touch the affected items.
        for (rel in survivingRelations) {
            relationKeys += rel.fromEntityId to rel.toEntityId
            relations += rel
        }

        // --- Wikilink edges (only for the affected pages) ------------------
        pageMeta.forEach { page ->
            val from = byName[EntityName.dedupKey(page.title)] ?: return@forEach
            page.links.forEach { link ->
                val to = byName[EntityName.dedupKey(link)] ?: return@forEach
                if (from.id == to.id) return@forEach
                val nameKey = (EntityName.dedupKey(from.name) to EntityName.dedupKey(to.name))
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

        // --- Analysis-JSON relations (only for the affected pages) ---------
        for (page in pageMeta) {
            val sourceId = page.item.sourceId ?: continue
            val analysis = analysisResultDao.getLatestBySource(sourceId) ?: continue
            for (rel in parseRelations(analysis.relationsJson)) {
                val from = byName[EntityName.dedupKey(rel.source)] ?: continue
                val to = byName[EntityName.dedupKey(rel.target)] ?: continue
                if (from.id == to.id) continue
                val nameKey = (EntityName.dedupKey(from.name) to EntityName.dedupKey(to.name))
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

        // --- Source-overlap edges (only the affected bucket) --------------
        val affectedPagesBySource = pageMeta.groupBy { it.sources.firstOrNull() ?: "" }
        for ((_, bucket) in affectedPagesBySource) {
            if (bucket.size < 2) continue
            for (i in bucket.indices) {
                for (j in i + 1 until bucket.size) {
                    val left = bucket[i]
                    val right = bucket[j]
                    val leftSet = left.sources.toSet()
                    if (right.sources.none { it in leftSet }) continue
                    val from = byName[EntityName.dedupKey(left.title)] ?: continue
                    val to = byName[EntityName.dedupKey(right.title)] ?: continue
                    val nameKey = (EntityName.dedupKey(from.name) to EntityName.dedupKey(to.name))
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
        // Re-derive communities for the affected source bucket only;
        // other communities (different source buckets) survive
        // unchanged.
        val newCommunities = pageMeta
            .groupBy { page -> page.sources.firstOrNull()?.takeIf { it.isNotBlank() } }
            .filter { (key, group) -> key != null && group.size >= 2 }
            .mapNotNull { (key, group) ->
                val keyStr = key ?: ""
                val communityName = "来源群 $keyStr"
                if (EntityName.dedupKey(communityName) in manuallyDeletedCommunityNames) {
                    null
                } else {
                    KnowledgeCommunityEntity(
                        id = UUID.randomUUID().toString(),
                        knowledgeBaseId = kbId,
                        name = communityName,
                        entityIdsJson = group.mapNotNull { byName[EntityName.dedupKey(it.title)] }
                            .joinToString(",", "[", "]") { "\"${it.id}\"" },
                        summary = group.take(6).joinToString("、") { it.title },
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null
                    )
                }
            }
        val finalCommunities = survivingCommunities + newCommunities
        graphDao.upsertCommunities(finalCommunities)
    }

    /**
     * One-shot backfill for knowledge bases whose `wiki_entity` /
     * `wiki_concept` pages were never materialised — the typical
     * symptom of the old ingest path that hard-coded
     *   entitiesJson = "[]"
     *   conceptsJson = tags.toJsonArray()
     *   relationsJson = "[]"
     * and never produced the wiki pages the graph rebuild relies on.
     *
     * Walks every source document in the KB, runs the local
     * `WikiPageCompiler` template against the latest
     * `AnalysisResultEntity`, and inserts the resulting
     * `wiki_entity` / `wiki_concept` rows. No LLM call. Safe to run
     * multiple times — pages that already exist for a given
     * `(sourceType, title)` are skipped.
     */
    override suspend fun backfillWikiPagesForBase(kbId: String): BackfillResult {
        if (kbId.isBlank()) return BackfillResult(0, 0, 0, 0)
        val compiler = WikiPageCompiler()
        val now = System.currentTimeMillis()
        val sourcesFlow = sourceDocumentDao.observeByKnowledgeBase(kbId)
        // `observeByKnowledgeBase` is a Flow — for a one-shot walk
        // we want the current snapshot, not a hot stream. The
        // source documents table is small (one row per ingest)
        // and backed by Room, so the first emission arrives in a
        // single query.
        val sourceList: List<SourceDocumentEntity> = sourcesFlow.first()
            .filter { it.status != SourceDocumentEntity.STATUS_DELETED }

        var entityInserted = 0
        var conceptInserted = 0
        var sourcesSkipped = 0

        for (source in sourceList) {
            val analysis = analysisResultDao.getLatestBySource(source.id)
            if (analysis == null || (analysis.entitiesJson == "[]" && analysis.conceptsJson == "[]")) {
                sourcesSkipped++
                continue
            }
            val drafts = compiler.compileEntityAndConceptPages(source, analysis)
            for (draft in drafts) {
                val existing = itemDao.getByKbSourceTypeAndTitle(kbId, draft.sourceType, draft.title)
                if (existing != null) continue
                val item = KnowledgeItemEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    sourceId = source.id,
                    knowledgeBaseId = kbId,
                    title = draft.title,
                    contentMarkdown = draft.markdown,
                    excerpt = draft.summary.take(120),
                    sourceType = draft.sourceType,
                    status = KnowledgeItemEntity.STATUS_ARCHIVED,
                    contentHash = calculateContentHash(draft.markdown),
                    sourceTraceJson = draft.sourceTraceJson,
                    confidence = analysis.confidence,
                    summary = draft.summary,
                    tagsJson = draft.tagsJson,
                    rawNoteId = null,
                    importance = if (draft.sourceType == "wiki_entity") 1 else 1,
                    createdAt = now,
                    updatedAt = now,
                    processedAt = now,
                    archivedAt = now,
                    deletedAt = null
                )
                itemDao.insert(item)
                when (draft.sourceType) {
                    "wiki_entity" -> entityInserted++
                    "wiki_concept" -> conceptInserted++
                }
            }
        }
        if (entityInserted > 0 || conceptInserted > 0) {
            rebuildGraphForBase(kbId)
        }
        return BackfillResult(
            sourcesScanned = sourceList.size,
            entityPagesInserted = entityInserted,
            conceptPagesInserted = conceptInserted,
            sourcesSkipped = sourcesSkipped,
        )
    }

    override suspend fun refreshOverviewForBase(kbId: String) {
        if (kbId.isBlank()) return
        val base = kbDao.getById(kbId) ?: return
        val now = System.currentTimeMillis()
        // 概览页要展示两类数据:原始知识(sourceItems)和 wiki 合成页。
        // 之前一次性 getAllByKb + Kotlin 端 filter,KB 笔记多时查询压力
        // 直接压在 Room 上。现在分两条查询:原始知识走 ALL,wiki 走
        // getAllWikiByKb——让 SQLite 利用 sourceType 上的索引/类型。
        val rawSourceItems = itemDao.getAllByKb(kbId)
            .filter { it.deletedAt == null }
            .filterNot { it.sourceType.startsWith("wiki_") }
        val wikiItems = itemDao.getAllWikiByKb(kbId)
            .filter { it.deletedAt == null }
            .filterNot { it.sourceType == "wiki_overview" && it.title == "overview.md" }
        val liveItems = rawSourceItems + wikiItems
        val sourceItems = rawSourceItems
        // wikiItems 已经排除了 overview 自身,保持原行为不变。
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

    // Keep ProfileScreen's "日志中心" description aligned with
    // KnowledgeLogScreen's summary cards: the log center is source-row
    // based, not raw-task based. A single source can have old retry /
    // canceled / superseded tasks, so counting all task rows can show
    // "4 条处理中" while the log center only has 3 source cards in that
    // state. Always group by source and inspect the latest task per source.
    override fun observeActiveTaskCount(): Flow<Int> =
        combine(sourceDocumentDao.observeAll(), taskDao.observeAllTasks()) { sources, tasks ->
            val tasksBySource = tasks.groupBy { task ->
                task.sourceId ?: task.targetId.takeIf { task.targetType == "source_document" }
            }
            sources.count { source ->
                val latest = tasksBySource[source.id].orEmpty().maxByOrNull { it.createdAt }
                latest?.status == "pending" ||
                    latest?.status == "running" ||
                    latest?.status == "pending_network"
            }
        }
            .distinctUntilChanged()

    override fun observeFailedTaskCount(): Flow<Int> =
        combine(sourceDocumentDao.observeAll(), taskDao.observeAllTasks()) { sources, tasks ->
            val tasksBySource = tasks.groupBy { task ->
                task.sourceId ?: task.targetId.takeIf { task.targetType == "source_document" }
            }
            sources.count { source ->
                val latest = tasksBySource[source.id].orEmpty().maxByOrNull { it.createdAt }
                latest?.status == "failed" || source.status == SourceDocumentEntity.STATUS_FAILED
            }
        }
            .distinctUntilChanged()

    override suspend fun retryTask(taskId: String) {
        taskDao.retryTask(taskId, System.currentTimeMillis())
    }

    override suspend fun retryProcessingForItem(itemId: String) {
        val item = itemDao.getById(itemId) ?: return
        val now = System.currentTimeMillis()
        item.sourceId?.let { sourceId ->
            taskDao.retryBySource(sourceId, now)
            sourceDocumentDao.updateStatus(sourceId, SourceDocumentEntity.STATUS_IMPORTED, null, now)
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
        // P5-merge: do NOT delete the previous analysis_result row.
        // The new run's `runAnalysisTask` reads it via
        // `analysisResultDao.getLatestBySource(source.id)` and unions
        // its entities / concepts JSON into the new analysis by
        // `name` (see `mergeEntityOrConceptByName` in
        // `IngestOrchestrator`). Without keeping the prior row, the
        // merge input is empty and re-analysis would wholesale
        // replace the entity inventory — the "存量实体与概念
        // 全部会被清除掉" symptom. The relations JSON is still
        // overwritten because the LLM has the global picture and
        // its new relations supersede any prior partial result.
        // (Callers that need a clean-slate re-derive should set
        // `inputJson = {"resetAnalysis":true}` and have
        // `runAnalysisTask` honour it — out of scope here.)
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
        // P5-merge: do NOT softDelete the wiki pages for this source.
        // The M2 (MERGE-1 PR-M2) upsert path in `runGenerationTask`
        // (`IngestOrchestrator.kt:858-911`) now does exact-then-slug
        // lookup: tries `getByKbSourceTypeAndTitle` first, and on
        // miss falls back to slug equality via
        // `Slug.slugify(title)`. This collapses the LLM-drift case
        // ("Accumulibacter" → "Candidatus Accumulibacter" between
        // re-ingest runs) back to in-place update instead of
        // inserting a duplicate row. With this in place, the old
        // `itemDao.softDeleteBySource` here would actively HIDE the
        // existing wiki pages from the M2 path (its queries filter
        // `deletedAt IS NULL`), forcing the slug-fallback path to
        // miss too, and re-introducing the "两个同名文件" symptom.
        //
        // Net effect of removing the softDelete:
        //   - M2 path's exact-title lookup: finds the existing row,
        //     updates in place (including clearing `deletedAt`).
        //   - M2 path's slug-fallback lookup: same — finds the row,
        //     updates in place. (Slug equality survives name drift.)
        //   - Items the new pipeline drops entirely: stay as live
        //     rows with their old content. Item count stays correct
        //     (M2 updates don't add rows). User can prune manually
        //     via the recycle bin if desired.
        //
        // Pre-existing page drafts that the new pipeline rewrites
        // keep their `id`; the per-item cleanup above (fragments,
        // embeddings, recommendations, conversations) still runs
        // against the *old* items and is safe because M2 restores
        // the row immediately after with `deletedAt = null`.
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

    override suspend fun retryProcessingForSourceFromLogCenter(sourceId: String) {
        // Log-center actions are presentation-side operations. They may
        // enqueue processing so the log can continue, but they must never
        // delete or hide knowledge-base content that the user already sees
        // in a library. In particular, do not clear parsed/analysis rows
        // and do not soft-delete knowledge_item rows here.
        //
        // Old task rows for this source ARE wiped, though: the log-center
        // card groups every task by sourceId and shows a stepper per stage
        // (parse / analysis / generation / embedding). If a previous run
        // left a `success` parse row and the new run produces a `failed`
        // parse row, the card ends up with status pill = "failed" but a
        // green stepper — i.e. "正常任务对应的同文件的失败任务" —
        // which is exactly the bug the log center should not surface.
        // The historical detail is still in `processing_task_log`, so
        // wiping the task rows doesn't lose the per-stage log trail.
        val source = sourceDocumentDao.getById(sourceId) ?: return
        val now = System.currentTimeMillis()
        taskDao.deleteBySource(sourceId)
        sourceDocumentDao.updateStatus(
            sourceId,
            SourceDocumentEntity.STATUS_IMPORTED,
            null,
            now
        )
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
                currentStep = "日志中心重新发起分析",
                inputJson = """{"sourceId":"$sourceId","reprocess":true,"origin":"log_center","preserveKnowledge":true}"""
            )
        )
        taskLogDao.insert(
            ProcessingTaskLogEntity(
                id = UUID.randomUUID().toString(),
                taskId = null,
                targetType = "source_document",
                targetId = source.id,
                stage = "retry",
                status = "pending",
                message = "日志中心重新发起分析：保留知识库已有条目，仅追加新的处理任务",
                createdAt = now
            )
        )
    }

    override suspend fun cancelTask(taskId: String) {
        taskDao.cancelTask(taskId, System.currentTimeMillis())
    }

    override suspend fun resetInterruptedRunningTasks(excludedTaskId: String?) {
        taskDao.resetInterruptedRunningTasks(excludedTaskId, System.currentTimeMillis())
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
            graphDao.observeAllEntities()
        ) { baseCount, itemCount, entities ->
            val conceptCount = entities.count { isKnowledgeConceptType(it.type) }
            val entityCount = entities.size - conceptCount
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
            // PERF-5: scoped rebuild — accepting a recommendation
            // only moves one item.
            if (base?.type != "unfiled") rebuildGraphForBaseAffected(kbId, setOf(recommendation.itemId))
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
        // P1: wiki_* 页面的图谱节点 type 现在优先读 frontmatter 的
        //   entityType (实体) / conceptCategory (概念)
        // 这两个字段是 LLM 给的语义类型("Person"/"Algorithm"/"Theory"/...)
        // ——之前被混到 `type:` 字段上,跟 generationPrompt 的 enum
        // 冲突,被中间处理数据页看到时
        // 已经是"entity"/"concept"两个大桶。这里把它们接回正确的字段。
        // 老数据(没有 entityType / conceptCategory 字段)回退到
        // "entity"/"concept";老 frontmatter `type:` 字段(等于"entity" /
        // "concept" / 老的 enum 值)继续兼容。
        return when (item.sourceType) {
            "wiki_entity" -> frontMatterValue(item.contentMarkdown, "entityType")
                ?.takeIf { it.isNotBlank() }
                ?: "entity"
            "wiki_concept" -> frontMatterValue(item.contentMarkdown, "conceptCategory")
                ?.takeIf { it.isNotBlank() }
                ?: "concept"
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

    // === Inspiration thread context (LLM input) ========================
    //
    // 一次性把 LLM 脉络生成需要的所有灵感原料拼好,worker 拿到这个
    // context 就能直接调大模型。不要把 wiki/source/file 等外部来源
    // 信息注入灵感脉络 prompt。
    override suspend fun getInspirationContext(
        kbId: String,
        newItemId: String,
    ): InspirationThreadContext {
        val newItem = itemDao.getById(newItemId)
        val newInspiration = if (newItem != null) {
            AiPromptTemplates.NewInspiration(
                id = newItem.id,
                title = newItem.title,
                tags = parseTagArray(newItem.tagsJson),
                summary = newItem.summary?.takeIf { it.isNotBlank() } ?: newItem.excerpt,
                content = newItem.contentMarkdown,
            )
        } else {
            // 罕见的 race:worker 调度时 newItem 已被删除。
            // 用一个 minimal 的 placeholder,让 worker 至少走完 fallback。
            AiPromptTemplates.NewInspiration(
                id = newItemId,
                title = "(已删除的灵感)",
                tags = emptyList(),
                summary = "",
                content = "",
            )
        }

        val allInKb = itemDao.getAllByKb(kbId)
            .filter { it.deletedAt == null }
            .sortedBy { it.createdAt }
        val historicalDigest = allInKb
            .asSequence()
            .filter { it.id != newItemId }
            .map { item ->
                AiPromptTemplates.InspirationDigest(
                    id = item.id,
                    title = item.title,
                    tags = parseTagArray(item.tagsJson),
                    summary = item.summary?.takeIf { it.isNotBlank() } ?: item.excerpt,
                    createdAtLabel = formatDateLabel(item.createdAt),
                )
            }
            .toList()
            .takeLast(30) // 只取最近 30 条历史,够脉络使用

        val existingThread = threadDao.getByKb(kbId)
        val existingSnapshot = if (existingThread != null) {
            AiPromptTemplates.ExistingThreadSnapshot(
                description = existingThread.description,
                coreQuestion = existingThread.coreQuestion,
                mainline = parseStringList(existingThread.mainlineJson),
                gaps = parseStringList(existingThread.gapsJson),
                nextSuggestions = parseStringList(existingThread.nextSuggestionsJson),
            )
        } else null

        return InspirationThreadContext(
            kbId = kbId,
            newInspiration = newInspiration,
            historicalInspirationDigest = historicalDigest,
            existingThread = existingSnapshot,
        )
    }

    //
    // 重新演化模式:用户主动点"重新演化"时调用。无单一 newItemId,
    // 拉最近 N 条 updatedAt 的灵感 full content + 较早历史的 digest +
    // 现有脉络。结构跟 InspirationThreadContext 不同,所以单独立方法。
    override suspend fun getInspirationReEvolveContext(
        kbId: String,
        recentCount: Int,
        historyCount: Int,
    ): InspirationReEvolveContext {
        val allInKb = itemDao.getAllByKb(kbId)
            .filter { it.deletedAt == null }
        // recent:按 updatedAt 从新到旧取前 recentCount
        val recentItems = allInKb.sortedByDescending { it.updatedAt }.take(recentCount)
        val recent = recentItems.map { it.toNewInspiration() }
        // history:剩下按 createdAt 从旧到新取 historyCount
        val history = allInKb
            .filter { item -> recentItems.none { it.id == item.id } }
            .sortedBy { it.createdAt }
            .take(historyCount)
            .map { it.toInspirationDigest() }
        val existingSnapshot = threadDao.getByKb(kbId)?.toExistingSnapshot()
        return InspirationReEvolveContext(
            kbId = kbId,
            recentInspiration = recent,
            historicalInspirationDigest = history,
            existingThread = existingSnapshot,
        )
    }

    // ---- 复用的实体映射 ----------------------------------------------------

    private fun com.my.knowledge.data.db.entity.KnowledgeItemEntity.toNewInspiration():
        AiPromptTemplates.NewInspiration = AiPromptTemplates.NewInspiration(
        id = id,
        title = title,
        tags = parseTagArray(tagsJson),
        summary = summary?.takeIf { it.isNotBlank() } ?: excerpt,
        content = contentMarkdown,
    )

    private fun com.my.knowledge.data.db.entity.KnowledgeItemEntity.toInspirationDigest():
        AiPromptTemplates.InspirationDigest = AiPromptTemplates.InspirationDigest(
        id = id,
        title = title,
        tags = parseTagArray(tagsJson),
        summary = summary?.takeIf { it.isNotBlank() } ?: excerpt,
        createdAtLabel = formatDateLabel(createdAt),
    )

    private fun com.my.knowledge.data.db.entity.KnowledgeThreadEntity.toExistingSnapshot():
        AiPromptTemplates.ExistingThreadSnapshot = AiPromptTemplates.ExistingThreadSnapshot(
        description = description,
        coreQuestion = coreQuestion,
        mainline = parseStringList(mainlineJson),
        gaps = parseStringList(gapsJson),
        nextSuggestions = parseStringList(nextSuggestionsJson),
    )

    private fun parseTagArray(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return runCatching {
            org.json.JSONArray(json).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") return emptyList()
        return runCatching {
            org.json.JSONArray(json).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }
            }
        }.getOrDefault(emptyList())
    }

    private fun formatDateLabel(epochMs: Long): String {
        val date = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "%d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
    }
}

/**
 * 灵感脉络 LLM 生成的输入快照。worker 拿到这个就直接拼 prompt。
 */
data class InspirationThreadContext(
    val kbId: String,
    val newInspiration: AiPromptTemplates.NewInspiration,
    val historicalInspirationDigest: List<AiPromptTemplates.InspirationDigest>,
    val existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
)

/**
 * 灵感脉络「重新演化」模式的输入快照。跟 [InspirationThreadContext] 的关键差异:
 * 没有单一 newInspiration,而是拉最近 N 条灵感的完整内容作为重写主导依据。
 * 详见 `KnowledgeRepository.getInspirationReEvolveContext`。
 */
data class InspirationReEvolveContext(
    val kbId: String,
    val recentInspiration: List<AiPromptTemplates.NewInspiration>,
    val historicalInspirationDigest: List<AiPromptTemplates.InspirationDigest>,
    val existingThread: AiPromptTemplates.ExistingThreadSnapshot?,
)
