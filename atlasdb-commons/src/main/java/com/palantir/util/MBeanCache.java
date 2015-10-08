package com.palantir.util;

/**
 * Registers an mbean useful for tracking cache operations. Subclasses are largely responsible for
 * updating the mbean on puts, cache hits, cache misses, etc.
 */
public abstract class MBeanCache<K, V> implements NonDistributedCache<K, V> {

    final protected CacheStats mbean = new CacheStats(this);

    volatile private String name = "MBeanCache";

    /**
     * Sets the name for this cache.  Useful
     * for debugging.
     *
     * @param name
     */
    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public abstract int getMaxCacheSize();
    public abstract void setMaxCacheSize(int size);

    public static final String OBJECT_NAME_PREFIX = "com.palantir.caching:type=";
    public String getStatBeanName() {
        return OBJECT_NAME_PREFIX + name;
    }


    /**
     * Registers an mbean for this cache with the provided object name.
     *
     * @param objectName
     *            the object name for the mbean
     */
    public void registerMBean(String objectName) {
        if (objectName == null) {
            throw new IllegalArgumentException("objectName must not be null.");
        }

        JMXUtils.registerMBeanCatchAndLogExceptions(mbean, objectName);
    }

    /**
     * Make sure to get a unique name on this object before trying to register this cache with JMX.
     * If it doens't have a unique name, then it won't be registered correctly.
     */
    public void registerMBean() {
        registerMBean(getStatBeanName());
    }

    public void collectStatsLoadTimeForMiss(long loadTimeInMillis) {
        mbean.loadTimeForMisses.addAndGet(loadTimeInMillis);
    }

    public void collectStatsLoadTimeForCacheKey(long loadTimeInMillis) {
        mbean.loadTimeForCacheKey.addAndGet(loadTimeInMillis);
    }

    @Override
    public abstract int size();

    /** Clears all entries from the cache.
     */
    @Override
    public abstract void clear();
}
