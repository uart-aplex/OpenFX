package com.openfx.core.common

import android.content.Context
import com.openfx.core.file.FileOperations
import com.openfx.core.file.LocalFileOperations
import com.openfx.core.network.Smb2Client
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindFileOperations(impl: LocalFileOperations): FileOperations
}

@Module
@InstallIn(SingletonComponent::class)
object CoreProviderModule {

    @Provides
    @Singleton
    fun provideSmb2Client(): Smb2Client = Smb2Client()
}
