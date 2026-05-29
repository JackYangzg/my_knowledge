package com.my.knowledge.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.my.knowledge.data.db.AppDatabase
import com.my.knowledge.data.file.LocalFileStore
import com.my.knowledge.data.processing.ProcessingTaskScheduler
import com.my.knowledge.data.repository.KnowledgeRepositoryImpl
import com.my.knowledge.data.repository.NoteRepositoryImpl
import com.my.knowledge.data.search.FtsSearchEngine
import com.my.knowledge.domain.usecase.AutoSaveNoteUseCase
import com.my.knowledge.domain.usecase.CreateNoteUseCase
import com.my.knowledge.viewmodel.*

object DependencyProvider {
    private var database: AppDatabase? = null
    private var fileStore: LocalFileStore? = null
    private var scheduler: ProcessingTaskScheduler? = null

    fun provideDatabase(context: Context): AppDatabase {
        return database ?: AppDatabase.getInstance(context).also { database = it }
    }

    fun provideFileStore(context: Context): LocalFileStore {
        return fileStore ?: LocalFileStore(context.applicationContext).also { fileStore = it }
    }
    
    fun provideScheduler(context: Context): ProcessingTaskScheduler {
        return scheduler ?: ProcessingTaskScheduler(context.applicationContext).also { scheduler = it }
    }

    fun provideNoteRepository(context: Context) = NoteRepositoryImpl(
        provideDatabase(context).noteDao(),
        provideFileStore(context)
    )

    fun provideKnowledgeRepository(context: Context) = KnowledgeRepositoryImpl(
        provideDatabase(context).knowledgeBaseDao(),
        provideDatabase(context).knowledgeItemDao()
    )
    
    fun provideSearchEngine(context: Context) = FtsSearchEngine(
        provideDatabase(context).searchDao()
    )
}

val ViewModelFactory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val context = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
        val knowledgeRepo = DependencyProvider.provideKnowledgeRepository(context)
        val noteRepo = DependencyProvider.provideNoteRepository(context)

        return when {
            modelClass.isAssignableFrom(NoteEditorViewModel::class.java) -> {
                NoteEditorViewModel(
                    CreateNoteUseCase(noteRepo),
                    AutoSaveNoteUseCase(noteRepo),
                    noteRepo
                ) as T
            }
            modelClass.isAssignableFrom(KnowledgeHomeViewModel::class.java) -> {
                KnowledgeHomeViewModel(knowledgeRepo) as T
            }
            modelClass.isAssignableFrom(KnowledgeManageViewModel::class.java) -> {
                KnowledgeManageViewModel(knowledgeRepo) as T
            }
            modelClass.isAssignableFrom(KnowledgeItemListViewModel::class.java) -> {
                KnowledgeItemListViewModel(knowledgeRepo) as T
            }
            modelClass.isAssignableFrom(ProfileViewModel::class.java) -> {
                ProfileViewModel() as T
            }
            modelClass.isAssignableFrom(AskViewModel::class.java) -> {
                AskViewModel(
                    knowledgeRepo,
                    DependencyProvider.provideSearchEngine(context)
                ) as T
            }
            modelClass.isAssignableFrom(ThreadViewModel::class.java) -> {
                ThreadViewModel(knowledgeRepo) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
