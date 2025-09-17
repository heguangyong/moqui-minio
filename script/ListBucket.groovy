import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue

ExecutionContext ec = context.ec

// Get the userId from the context
String userId = context.userId

// Find buckets for the specified user
EntityList bucketList = ec.entity.find("moqui.netdisk.Bucket").condition("userId", userId).list()

// Put the bucket list in the context
context.bucketList = bucketList