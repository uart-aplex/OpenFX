package com.openfx.core.common

import com.openfx.core.file.FileOperations
import com.openfx.core.file.RoutingFileOperations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    /** 依路徑分派到本機或 SMB (smb://) 的實作 */
    @Binds
    @Singleton
    abstract fun bindFileOperations(impl: RoutingFileOperations): FileOperations
}
