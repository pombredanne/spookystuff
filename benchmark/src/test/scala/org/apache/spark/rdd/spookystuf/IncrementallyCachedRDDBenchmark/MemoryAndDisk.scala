package org.apache.spark.rdd.spookystuf.IncrementallyCachedRDDBenchmark

import org.apache.spark.storage.StorageLevel

class MemoryAndDisk extends Abstract {

  override val storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK_SER
}
