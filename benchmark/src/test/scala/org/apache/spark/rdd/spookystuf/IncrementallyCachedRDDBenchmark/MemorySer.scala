package org.apache.spark.rdd.spookystuf.IncrementallyCachedRDDBenchmark

import org.apache.spark.storage.StorageLevel

class MemorySer extends Abstract {

  override val storageLevel: StorageLevel = StorageLevel.MEMORY_ONLY_SER
}
