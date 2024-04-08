package kotlinx.atomicfu.locks

import platform.posix.*
import interop.*
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.*
import kotlinx.cinterop.NativePtr
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicNativePtr
import kotlin.experimental.ExperimentalNativeApi


private val threadCounter = atomic(0)

// TODO assert no overflow?
@kotlin.native.concurrent.ThreadLocal
private var threadId: UInt = threadCounter.addAndGet(1).toUInt()

public actual open class SynchronizedObject {

    private enum class LockStatus { UNLOCKED, THIN, FAT }

    @OptIn(ExperimentalNativeApi::class)
    private value class LockWord private constructor(private val encoded: ULong) {
        companion object {
            private const val FAT_BIT_MASK = 1uL

            fun unlocked() = Thin(0u, 0u).lockWord

            fun fromLong(l: Long) = LockWord(l.toULong())
        }

        inline val fat: Boolean get() = encoded.and(FAT_BIT_MASK) != 0uL
        inline val thin: Boolean get() = !fat

        inline val status: LockStatus get() = when {
            thin -> if (asThin().isUnlocked()) LockStatus.UNLOCKED else LockStatus.THIN
            else -> LockStatus.FAT
        }

        inline fun asThin() = Thin(this)
        inline fun asFat() = Fat(this)

        inline fun toLong() = encoded.toLong()

        value class Thin internal constructor(val lockWord: LockWord) {
            init { assert(lockWord.thin) }

            companion object {
                // TODO outline some consts

                inline operator fun invoke(nested: UInt, ownerTid: UInt): Thin {
                    if (nested > UInt.MAX_VALUE.shr(1)) throw IllegalArgumentException() // TODO
                    val nestedPart = nested.shl(1).toULong()
                    val tidPart = ownerTid.toULong().shl(UInt.SIZE_BITS)
                    val result = Thin(LockWord(nestedPart.or(tidPart)))
                    assert(result.nested == nested)
                    assert(result.ownerTid == ownerTid)
                    return result
                }
            }

            inline val nested: UInt get() = lockWord.encoded.and(UInt.MAX_VALUE.toULong()).shr(1).toUInt()
            inline val ownerTid: UInt get() = lockWord.encoded.and(UInt.MAX_VALUE.toULong().inv()).shr(UInt.SIZE_BITS).toUInt()

            internal inline fun isUnlocked() = ownerTid == 0u
        }

        value class Fat internal constructor(val lockWord: LockWord) {
            init { assert(lockWord.fat) }

            companion object {
                inline operator fun invoke(mutex: CPointer<mutex_node_t>, contended: Boolean = false): Fat {
                    val mutexPtrValue = mutex.toLong().toULong()
                    assert(mutexPtrValue.and(FAT_BIT_MASK) == 0uL)
                    return Fat(LockWord(mutexPtrValue.or(FAT_BIT_MASK)))
                }
            }

            inline val mutex: CPointer<mutex_node_t> get() =
                lockWord.encoded.and(FAT_BIT_MASK.inv()).toLong().toCPointer()!!
        }

    }

    // TODO introduce AtomicLockWord
    private val lockWord = AtomicLong(LockWord.unlocked().toLong())

    private inline fun loadLockState() = LockWord.fromLong(lockWord.value)

    private fun compareSetAndFreeLock(expected: LockWord, desired: LockWord): Boolean {
        return lockWord.compareAndSet(expected.toLong(), desired.toLong())
    }

    public fun lock() {
        val currentThreadId = threadId
        while (true) {
            val state = loadLockState()
            when (state.status) {
                LockStatus.UNLOCKED -> {
                    val thinLock = LockWord.Thin(1u, currentThreadId)
                    if (compareSetAndFreeLock(state, thinLock.lockWord))
                        return
                }
                LockStatus.THIN -> {
                    val thinState = state.asThin()
                    if (currentThreadId == thinState.ownerTid) {
                        // reentrant lock
                        val thinNested = LockWord.Thin(thinState.nested + 1u, currentThreadId)
                        if (compareSetAndFreeLock(state, thinNested.lockWord))
                            return
                    } else {
                        // another thread holds the lock -> allocate native mutex
                        // TODO allocate native mutex
                        // or just spin
                        pthread_yield_np()
                    }
                }
                LockStatus.FAT -> {
                    abort()
                }
            }
        }
    }

    public fun tryLock(): Boolean {
        val currentThreadId = threadId
        while (true) {
            val state = loadLockState()
            if (state.status == LockStatus.UNLOCKED) {
                val thinLock = LockWord.Thin(1u, currentThreadId)
                if (compareSetAndFreeLock(state, thinLock.lockWord))
                    return true
            } else {
                // FIXME what if fat?
                if (currentThreadId == state.asThin().ownerTid) {
                    val nestedLock = LockWord.Thin(state.asThin().nested + 1u, currentThreadId)
                    if (compareSetAndFreeLock(state, nestedLock.lockWord))
                        return true
                } else {
                    return false
                }
            }
        }
    }

    public fun unlock() {
        val currentThreadId = threadId
        while (true) {
            val state = loadLockState()
            when (state.status) {
                LockStatus.THIN -> {
                    val thinState = state.asThin()
                    require(currentThreadId == thinState.ownerTid) { "Thin lock may be only released by the owner thread, expected: ${thinState.ownerTid}, real: $currentThreadId" }
                    // nested unlock
                    if (thinState.nested == 1u) {
                        val unlocked = LockWord.unlocked()
                        if (compareSetAndFreeLock(state, unlocked))
                            return
                    } else {
                        val releasedNestedLock = LockWord.Thin(thinState.nested - 1u, thinState.ownerTid)
                        if (compareSetAndFreeLock(state, releasedNestedLock.lockWord))
                            return
                    }
                }
                LockStatus.FAT -> {
                    require(false)
                }
                else -> error("It is not possible to unlock the mutex that is not obtained")
            }
        }
    }


    private fun CPointer<mutex_node_t>.lock() = lock(this.pointed.mutex)

    private fun CPointer<mutex_node_t>.unlock() = unlock(this.pointed.mutex)
}

public actual fun reentrantLock() = ReentrantLock()

public actual typealias ReentrantLock = SynchronizedObject

public actual inline fun <T> ReentrantLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

public actual inline fun <T> synchronized(lock: SynchronizedObject, block: () -> T): T {
    lock.lock()
    try {
        return block()
    } finally {
        lock.unlock()
    }
}

private const val INITIAL_POOL_CAPACITY = 64

private val mutexPool by lazy { MutexPool(INITIAL_POOL_CAPACITY) }

class MutexPool(capacity: Int) {
    private val top = AtomicNativePtr(NativePtr.NULL)

    private val mutexes = nativeHeap.allocArray<mutex_node_t>(capacity) { mutex_node_init(ptr) }

    init {
        for (i in 0 until capacity) {
            release(interpretCPointer<mutex_node_t>(mutexes.rawValue.plus(i * sizeOf<mutex_node_t>()))!!)
        }
    }

    private fun allocMutexNode() = nativeHeap.alloc<mutex_node_t> { mutex_node_init(ptr) }.ptr

    fun allocate(): CPointer<mutex_node_t> = pop() ?: allocMutexNode()

    fun release(mutexNode: CPointer<mutex_node_t>) {
        while (true) {
            val oldTop = interpretCPointer<mutex_node_t>(top.value)
            mutexNode.pointed.next = oldTop
            if (top.compareAndSet(oldTop.rawValue, mutexNode.rawValue))
                return
        }
    }

    private fun pop(): CPointer<mutex_node_t>? {
        while (true) {
            val oldTop = interpretCPointer<mutex_node_t>(top.value)
            if (oldTop.rawValue === NativePtr.NULL)
                return null
            val newHead = oldTop!!.pointed.next
            if (top.compareAndSet(oldTop.rawValue, newHead.rawValue))
                return oldTop
        }
    }
}
