package si.mycomp.requestDumpingHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConcurrentArrayList<T> {

	/** use this to lock for write operations like add/remove */
	private final Lock readLock;
	/** use this to lock for read operations like get/iterator/contains.. */
	private final Lock writeLock;
	/** the underlying list */
	private final List<T> list = new ArrayList<T>();

	{
		ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
		readLock = rwLock.readLock();
		writeLock = rwLock.writeLock();
	}

	public void push(T e) {
		writeLock.lock();
		try {
			list.add(e);
		} finally {
			writeLock.unlock();
		}
	}

	public T pop(int index) {
		T res;
		readLock.lock();
		try {
			res = list.get(index);
			list.remove(index);
		} finally {
			readLock.unlock();
		}
		return res;
	}

	public Iterator<T> iterator() {
		readLock.lock();
		try {
			return new ArrayList<T>(list).iterator();
			// ^ we iterate over an snapshot of our list
		} finally {
			readLock.unlock();
		}
	}
	
	public int size()
	{
		return list.size();
	}
}