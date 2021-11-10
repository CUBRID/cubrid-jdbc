package cubrid.jdbc.jci;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

public class UPreparedStatementCache<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;
    protected int maxElements;

    public UPreparedStatementCache(int maxSize) {
        super(maxSize, 0.75F, true);
        this.maxElements = maxSize;
    }

    @Override
    public V get(Object key) {
        synchronized (this) {
            return super.get(key);
        }
    }

    @Override
    public V put(K key, V value) {
        synchronized (this) {
            return super.put(key, value);
        }
    }

    @Override
    public V remove(Object key) {
        synchronized (this) {
            return super.remove(key);
        }
    }

    @Override
    protected boolean removeEldestEntry(Entry<K, V> eldest) {
        return (size() > this.maxElements);
    }
}
