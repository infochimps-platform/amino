package com._42six.amino.common.service.datacache;

import com._42six.amino.common.util.PathUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.shared.SharedCount;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapFile;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * A lookup table for helping to compress data in the MapReduce jobs.  This takes a SortedSet of String values, creates
 * an index number for each one, and persists the map to HDFS.  This allows the user to compress strings to integer values
 * and at the same time allows the the sorters to work properly by sorting the indexes based on how the underlying value
 * would have been lexigraphically sorted.
 *
 * NOTE!
 * Serializing the cache to HDFS is not thread safe.  You must do this as a singleton operation or you will get incorrect results
 */
public class SortedIndexCache  {

    private static final Logger logger = LoggerFactory.getLogger(SortedIndexCache.class);
    private static final String LOCK_PATH = "/ezbatch/amino/LUTLock";

    /** The map indexed map of values */
    protected ImmutableMap<IntWritable, String> dataMap;
    protected SortedSet<String> valuesToStore = new TreeSet<>();
    protected String subFolder;
    protected Configuration conf;

    private InterProcessMutex lock;

    public SortedIndexCache(String subFolder, Configuration conf) throws IOException {
        Preconditions.checkNotNull(subFolder);
        Preconditions.checkNotNull(conf);
        if(!subFolder.startsWith("/")){
            subFolder = "/" + subFolder;
        }
        this.subFolder = subFolder;
        this.conf = conf;
    }

    public void loadFromStorage() throws IOException {
        final HashMap<IntWritable, String> mapFromDisk = new HashMap<>();
        final IntWritable key = new IntWritable();
        final Text value = new Text();

        final FileSystem fs = FileSystem.get(conf);
        for(String cachePath : PathUtils.getCachePaths(conf)) {
            final String cacheFolder = PathUtils.concat(cachePath, subFolder);
            if(fs.exists(new Path(cacheFolder))){
                try(MapFile.Reader reader = new MapFile.Reader(FileSystem.get(conf), cacheFolder, conf)) {
                    while(reader.next(key, value)){
                        final IntWritable mfdKey = new IntWritable(key.get());
                        if(mapFromDisk.containsKey(mfdKey)){
                            logger.error("Index collision.  Attempting to load {}:{} but there is already the value {}:{}",
                                    key.toString(), value.toString(), key.toString(), mapFromDisk.get(mfdKey));
                            throw new IOException("Index collision");
                        }
                        mapFromDisk.put(mfdKey, value.toString());
                    }
                }
            } else {
                fs.mkdirs(new Path(cacheFolder));
            }
        }

        // We don't want the user to be able to change the map as it will change the order of items causing things pointing
        // to this cache to be invalid
        dataMap = ImmutableMap.copyOf(mapFromDisk);
    }

    /**
     * Fetches a unique index key from Zookeeper to help deconflict when we combine caches from different jobs together
     * @return The unique index key
     */
    private int fetchUniqueKey(SharedCount counter) throws Exception {
        int retVal;
        int newCount;
        boolean updated = false;
        do{
            retVal = counter.getCount();
            newCount = retVal + 1;
            updated = counter.trySetCount(newCount);
        } while(!updated);
        return retVal;
    }

    /**
     * Serialize the values out to HDFS.  This will fetch unique IDs from the global cache index to assure combining
     * cache lookups won't be messed up
     */
    public void persist() throws Exception {
        final String cachePath = PathUtils.concat(PathUtils.getCachePath(conf), subFolder);
        logger.info("Writing cache data to: " + cachePath);
        final String connectString = conf.get("dataloader.zookeepers"); // TODO UNDO MAGIC STRING
        Preconditions.checkNotNull(connectString, "Could not find Zookeepers in the config");
        final CuratorFramework client = CuratorFrameworkFactory.newClient(connectString, new ExponentialBackoffRetry(1000, 3));
        client.start();
        final SharedCount counter = new SharedCount(client, LOCK_PATH, 0);
        counter.start();

        try(FileSystem fs = FileSystem.get(conf)){
            try(MapFile.Writer writer = new MapFile.Writer(conf, fs, PathUtils.getCachePath(conf) + subFolder, IntWritable.class, Text.class)) {
                final Text value = new Text();
                final IntWritable key = new IntWritable();
                for(String v : valuesToStore){
                    key.set(fetchUniqueKey(counter));
                    value.set(v);
                    logger.debug("Storing {} : {}", key, value);
                    writer.append(key, value);
                }
            }
        } finally {
            counter.close();
            client.close();
        }

    }

    public void addValue(String value){
        valuesToStore.add(value);
    }

    public void addValues(Collection<? extends String> values){
        valuesToStore.addAll(values);
    }

    /**
     * Returns the index for the given value
     * @param value The value to look up
     * @return The cache index for this value, Integer.MIN_VALUE otherwise
     */
    public int getIndexForValue(String value){
        for(Map.Entry<IntWritable, String> entry : dataMap.entrySet()){
            if(entry.getValue().compareTo(value) == 0){
                // Return the int instead of the key so that users don't accidentally modify the key
                return entry.getKey().get();
            }
        }
        return Integer.MIN_VALUE;
    }

    public int getIndexForValue(Text value){
        return getIndexForValue(value.toString());
    }

    /**
     * Retrieves an item from the cache using its key
     * @param key The lookup key for the cache
     * @return The value associated with the key, or null if the key is not in the map
     */
    public String getItem(IntWritable key){
        // Strings are immutable so we can just return the
        return dataMap.get(key);
    }

    @Override
    public String toString(){
        return dataMap.toString();
    }

}
