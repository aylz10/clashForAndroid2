package com.github.kr328.clash.service

import android.content.Context
import android.util.Log
import com.github.kr328.clash.service.data.*
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.io.FileNotFoundException
import java.util.*

class ProfileManager(private val context: Context) : IProfileManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            Database.database //.init

            ProfileReceiver.rescheduleAll(context)
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload=0,
            total = 0,
            download = 0,
            expire = 0,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun clone(uuid: UUID): UUID {
        val newUUID = generateProfileUUID()

        val imported = ImportedDao().queryByUUID(uuid)
            ?: throw FileNotFoundException("profile $uuid not found")

        val pending = Pending(
            uuid = newUUID,
            name = imported.name,
            type = Profile.Type.File,
            source = imported.source,
            interval = imported.interval,
            upload=imported.upload,
            total = imported.total,
            download = imported.download,
            expire = imported.expire,
        )

        cloneImportedFiles(uuid, newUUID)

        PendingDao().insert(pending)

        return newUUID
    }

    override suspend fun patch(uuid: UUID, name: String, source: String, interval: Long) {
        val pending = PendingDao().queryByUUID(uuid)

        if (pending == null) {
            val imported = ImportedDao().queryByUUID(uuid)
                ?: throw FileNotFoundException("profile $uuid not found")

            cloneImportedFiles(uuid)

            PendingDao().insert(
                Pending(
                    uuid = imported.uuid,
                    name = name,
                    type = imported.type,
                    source = source,
                    interval = interval,
                    upload=0,
                    total = 0,
                    download = 0,
                    expire = 0,
                )
            )
        } else {
            val newPending = pending.copy(
                name = name,
                source = source,
                interval = interval,
                upload=0,
                total = 0,
                download = 0,
                expire = 0,
            )

            PendingDao().update(newPending)
        }
    }

    override suspend fun update(uuid: UUID) {
        scheduleUpdate(uuid, true)
        ImportedDao().queryByUUID(uuid)?.let {
            if(it.type==Profile.Type.Url){
                updateFlow(it)
            }

        }

    }
    suspend fun updateFlow(old: Imported) {
        val client = OkHttpClient()
        try {
            val request = Request.Builder()
           .url(old?.source+"&flag=clash")
//                .url("https://blog.csdn.net")
                .header("User-Agent", "ClashforWindows/0.19.23")
                .build()

            client.newCall(request).execute().use { response ->
                val userinfo=response.headers["subscription-userinfo"]
                if (!response.isSuccessful||userinfo==null) return
                var upload: Long =0
                var download: Long =0
                var total: Long =0
                var expire: Long =0
                if (userinfo.split(";")?.size!! >0 && userinfo.split(";")?.get(0)?.split("=")?.size!! >1){
                    upload= userinfo.split(";")?.get(0)?.split("=")
                        ?.get(1)
                        ?.toLong()
                        ?: 0
                }
                if (userinfo.split(";")?.size!! >1 && userinfo.split(";")?.get(1)?.split("=")?.size!! >1){
                    download= userinfo.split(";")?.get(1)?.split("=")
                        ?.get(1)
                        ?.toLong()
                        ?: 0
                }
                if (userinfo.split(";")?.size!! >2 && userinfo.split(";")?.get(2)?.split("=")?.size!! >1){
                    total=userinfo.split(";")?.get(2)?.split("=")
                        ?.get(1)
                        ?.toLong()
                        ?: 0
                }
                if (old.expire>0){
                    expire=old.expire
                }else if (userinfo.split(";")?.size!! >3 && userinfo.split(";")?.get(3)?.split("=")?.size!! >1){
                    var expireStr=userinfo.split(";")?.get(3)?.split("=")
                        ?.get(1);
                    if (expireStr?.count()!! >0){
                        expire =expireStr.toLong()

                    }
                }else{
                    expire=0
                }
                val new = Imported(
                    old.uuid,
                    old.name,
                    old.type,
                    old.source,
                    old.interval,
                    upload,
                    download,
                    total,
                    expire,
                    old?.createdAt ?: System.currentTimeMillis()
                )

                if (old != null) {
                    ImportedDao().update(new)
                } else {
                    ImportedDao().insert(new)
                }

                PendingDao().remove(new.uuid)
                context.sendProfileChanged(new.uuid)
                // println(response.body!!.string())
            }

        }catch (e: Exception) {
           System.out.println(e)
        }


    }
    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        ProfileProcessor.apply(context, uuid, callback)

        scheduleUpdate(uuid, false)
    }

    override suspend fun release(uuid: UUID) {
        ProfileProcessor.release(context, uuid)
    }

    override suspend fun delete(uuid: UUID) {
        ImportedDao().queryByUUID(uuid)?.also {
            ProfileReceiver.cancelNext(context, it)
        }

        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? {
        return resolveProfile(uuid)
    }

    override suspend fun queryAll(): List<Profile> {
        val uuids = withContext(Dispatchers.IO) {
            (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()
        }

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid,
            name,
            type,
            source,
            active != null && imported?.uuid == active,
            interval,
            upload,
            download,
            total,
            expire,
            resolveUpdatedAt(uuid),
            imported != null,
            pending != null
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long {
        return context.pendingDir.resolve(uuid.toString()).directoryLastModified
            ?: context.importedDir.resolve(uuid.toString()).directoryLastModified
            ?: -1
    }

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists())
            throw FileNotFoundException("profile $source not found")

        t.deleteRecursively()

        s.copyRecursively(t)
    }

    private suspend fun scheduleUpdate(uuid: UUID, startImmediately: Boolean) {
        val imported = ImportedDao().queryByUUID(uuid) ?: return

        if (startImmediately) {
            ProfileReceiver.schedule(context, imported)
        } else {
            ProfileReceiver.scheduleNext(context, imported)
        }
    }
}