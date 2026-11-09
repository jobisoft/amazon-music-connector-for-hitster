package de.jobisoft.hitsterconnector.di

import android.content.Context
import de.jobisoft.hitsterconnector.data.local.ConfigDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideConfigDataStore(
        @ApplicationContext context: Context
    ): ConfigDataStore {
        return ConfigDataStore(context)
    }
}
