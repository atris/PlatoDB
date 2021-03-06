package org.atri.platodb.entity;

/*
 *@author atri
 * Licensed to PlatoDB
 * 
 *
 * 
 *
 *
 *    
 *
 *
 *
 *
 *
 *.
 */


import org.atri.platodb.entity.serialization.HashCodeCalculator;
import org.atri.platodb.entity.serialization.Marshaller;
import org.atri.platodb.entity.serialization.Unmarshaller;
import org.atri.platodb.store.Store;
import org.atri.platodb.store.lock.Lock;
import org.atri.platodb.store.lock.NativeFSLockFactory;
import org.atri.platodb.store.sequence.FilebasedSequenceManager;
import org.atri.platodb.store.sequence.SequenceManager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * @see org.atri.platodb.store.Store
 * @see org.atri.platodb.entity.PrimaryIndex
 * @see org.atri.platodb.entity.Transaction
 */
public class EntityStore {

  private Queue<RandomAccessFile> metadataRAFs = new ConcurrentLinkedQueue<RandomAccessFile>();

  private static class Metadata {
    private int fileFormatVersion;
    private long storeRevision;
    private String name;
  }

  public void readMetadata(Metadata metadata, RandomAccessFile RAF) throws IOException {
    RAF.seek(0);
    metadata.fileFormatVersion = RAF.readInt();
    metadata.storeRevision = RAF.readLong();
    metadata.name = RAF.readUTF();
    System.currentTimeMillis();
  }

  public void writeMetadata(Metadata metadata, RandomAccessFile RAF) throws IOException {
    RAF.seek(0);
    RAF.writeInt(metadata.fileFormatVersion);
    RAF.writeLong(metadata.storeRevision);
    RAF.writeUTF(metadata.name);
  }

  private RandomAccessFile borrowMetadataRAF() throws IOException {
    RandomAccessFile raf = metadataRAFs.poll();
    if (raf == null) {
      File metadataFile = new File(configuration.getDataPath(), "metadata");
      if (!metadataFile.exists()) {
        metadataFile.createNewFile();
        raf = new RandomAccessFile(metadataFile, "rw");
        Metadata metadata = new Metadata();
        metadata.fileFormatVersion = 1;
        metadata.storeRevision = 0;
        metadata.name = "";
        writeMetadata(metadata, raf);
      } else {
        raf = new RandomAccessFile(metadataFile, "rw");
      }

    }
    return raf;
  }

  private void returnMetdataRAF(RandomAccessFile raf) throws IOException {
    if (metadataRAFs.size() > 2) {
      raf.close();
    } else {
      metadataRAFs.add(raf);
    }
  }

  public long getStoreRevision() throws IOException {
    Metadata metadata = new Metadata();
    RandomAccessFile metadataRAF = borrowMetadataRAF();
    readMetadata(metadata, metadataRAF);
    returnMetdataRAF(metadataRAF);
    return metadata.storeRevision;
  }

  public long increaseStoreRevision() throws IOException {
    return new Lock.With<Long>(getStoreWriteLock(), getConfiguration().getLockWaitTimeoutMilliseconds()) {
      public Long doBody() throws IOException {
        Metadata metadata = new Metadata();
        RandomAccessFile metadataRAF = borrowMetadataRAF();

        readMetadata(metadata, metadataRAF);
        metadata.storeRevision++;
        writeMetadata(metadata, metadataRAF);

        returnMetdataRAF(metadataRAF);
        return metadata.storeRevision;
      }
    }.run();
  }

  private Lock storeWriteLock;

  public Lock getStoreWriteLock() {
    return storeWriteLock;
  }

  private org.atri.platodb.entity.Configuration configuration;


  public EntityStore(File dataPath) throws IOException {
    this(new org.atri.platodb.entity.Configuration(dataPath));
  }

  /**
   * @param configuration immutable configuration, will be copied to each primary index store.
   */
  public EntityStore(org.atri.platodb.entity.Configuration configuration) {
    this.configuration = configuration;


  }

  private SequenceManager sequenceManager;

  public SequenceManager getSequenceManager() {
    return sequenceManager;
  }

  public void setSequenceManager(SequenceManager sequenceManager) {
    this.sequenceManager = sequenceManager;
  }

  public void open() throws IOException {

    File sequencePath = new File(configuration.getDataPath(), "seq");
    sequenceManager = new FilebasedSequenceManager(sequencePath, configuration.getLockFactory(), configuration.getLockWaitTimeoutMilliseconds());

    storeWriteLock = configuration.getLockFactory().makeLock("EntityStore metadata lock");
  }

  public org.atri.platodb.entity.Configuration getConfiguration() {
    return configuration;
  }

  private Map<String, PrimaryIndex> primaryIndexByName = new HashMap<String, PrimaryIndex>();
  private Map<String, Store> storeByPrimaryIndexName = new HashMap<String, Store>();

  public <PK, V> PrimaryIndex<PK, V> getPrimaryIndex(Class<PK> keyClass, Class<V> entityClass) throws IOException {
    return getPrimaryIndex(keyClass, entityClass, entityClass.getName());
  }

  /**
   *
   * @param keyClass
   * @param entityClass
   * @param primaryIndexName
   * @param <PK>
   * @param <V>
   * @return
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public <PK, V> PrimaryIndex<PK, V> getPrimaryIndex(Class<PK> keyClass, Class<V> entityClass, String primaryIndexName) throws IOException {
    PrimaryIndex<PK, V> primaryIndex = primaryIndexByName.get(primaryIndexName);
    if (primaryIndex == null) {

      if (!entityClass.isAnnotationPresent(Entity.class)) {
        throw new RuntimeException("Entity class " + entityClass.getName() + " is not annotated with @Entity");
      }

      // todo dont copy, allow setting per primary index.
      org.atri.platodb.store.Configuration storeconf = new org.atri.platodb.store.Configuration(new File(configuration.getDataPath(), primaryIndexName));
      storeconf.setAutomaticRehashCapacityGrowFactor(configuration.getAutomaticRehashCapacityGrowFactor());
      storeconf.setAutomaticRehashThreadshold(configuration.getAutomaticRehashThreadshold());
      storeconf.setHashCodesPartitionByteSize(configuration.getHashCodesPartitionByteSize());
      storeconf.setInitialCapacity(configuration.getInitialCapacity());
      storeconf.setKeysPartitionByteSize(configuration.getKeysPartitionByteSize());
      storeconf.setLockFactory(configuration.getLockFactory());      
      storeconf.setLockWaitTimeoutMilliseconds(configuration.getLockWaitTimeoutMilliseconds());
      storeconf.setUsingDurablePostingLinks(configuration.isUsingDurablePostingLinks());
      storeconf.setValuesPartitionByteSize(configuration.getValuesPartitionByteSize());

      storeconf.setLockFactory(new NativeFSLockFactory(storeconf.getDataPath()));

      // todo notice that the lock factory creates entity store wide locks
      // todo even when only attempting to lock a single store as in Accessor

      Store store = new Store(storeconf);
      store.open();
      storeByPrimaryIndexName.put(primaryIndexName, store);

      Method primaryKeyGetter;
      Method primaryKeySetter;
      SequenceManager.Sequence<PK> primaryKeySequence = null;
      {
        List<Field> pkFields = new ArrayList<Field>();
        for (Field field : entityClass.getDeclaredFields()) {
          if (field.isAnnotationPresent(PrimaryKey.class)) {
            pkFields.add(field);
          }
        }
        if (pkFields.size() == 0) {
          throw new RuntimeException("No field in class " + entityClass.getName() + " annotated with @PrimaryKey");
        } else if (pkFields.size() > 1) {
          StringBuilder sb = new StringBuilder(1000);
          for (Field pkField : pkFields) {
            if (sb.length() > 0) {
              sb.append(", ");
            }
            sb.append(pkField.getName());
          }
          throw new RuntimeException("Multiple fields in class " + entityClass.getName() + "  annotated with @PrimaryKey: " + sb.toString());
        }
        Field primaryKeyField = pkFields.get(0);
        StringBuffer name = new StringBuffer(primaryKeyField.getName());
        name.setCharAt(0, Character.toUpperCase(name.charAt(0)));
        String getterName = "get" + name.toString();
        try {
          primaryKeyGetter = entityClass.getMethod(getterName);
        } catch (NoSuchMethodException e) {
          throw new RuntimeException("@PrimaryKey field " + primaryKeyField.getName() + " of @Entity class " + entityClass.getName() + " does not have a getter method named " + getterName);
        }
        String setterName = "set" + name.toString();
        try {
          primaryKeySetter = entityClass.getMethod(setterName, primaryKeyField.getType());
        } catch (NoSuchMethodException e) {
          throw new RuntimeException("@PrimaryKey field " + primaryKeyField.getName() + " of @Entity class " + entityClass.getName() + " does not have a setter method named " + setterName);
        }

        Sequence sequence = primaryKeyField.getAnnotation(Sequence.class);
        if (sequence != null) {
          if ("[unassigned]".equals(sequence.name())) {
            primaryKeySequence = getSequenceManager().getOrRegisterSequence(keyClass, sequence.name());
          } else {
            primaryKeySequence = getSequenceManager().getOrRegisterSequence(keyClass, sequence.name());
          }
        }
      }

      Marshaller keyMarshaller = getConfiguration().getSerializationRegistry().getMarshaller(keyClass);
      Unmarshaller keyUnmarshaller = getConfiguration().getSerializationRegistry().getUnmarshaller(keyClass);
      HashCodeCalculator keyHashCodeCalculator = getConfiguration().getSerializationRegistry().getHashCodeCalcualtor(keyClass);
      Marshaller entityMarshaller = getConfiguration().getSerializationRegistry().getMarshaller(entityClass);
      Unmarshaller entityUnmarshaller = getConfiguration().getSerializationRegistry().getUnmarshaller(entityClass);

      primaryIndex = new PrimaryIndex<PK, V>(
          store, this,
          primaryIndexName,
          primaryKeySequence,
          primaryKeyGetter, primaryKeySetter,
          keyClass, entityClass,
          keyMarshaller, keyUnmarshaller, keyHashCodeCalculator,
          entityMarshaller, entityUnmarshaller
      );

      primaryIndexByName.put(primaryIndexName, primaryIndex);
    } else {
      if (!primaryIndex.getKeyClass().equals(keyClass)) {
        throw new IllegalArgumentException("Key type " + keyClass.getName() + " does not match registred type " + primaryIndex.getKeyClass().getName());
      }
      if (!primaryIndex.getEntityClass().equals(entityClass)) {
        throw new IllegalArgumentException("Entity type " + entityClass.getName() + " does not match registred type " + primaryIndex.getEntityClass().getName());
      }
    }

    return primaryIndex;
  }

  private ThreadLocal<Transaction> transactions = new ThreadLocal<Transaction>() {
    @Override
    protected Transaction initialValue() {
      return new Transaction(EntityStore.this);
    }
  };

  /**
   * @return A thread local transaction.
   */
  public Transaction getTxn() {
    return transactions.get();
  }

  public void close() throws IOException {
    // todo abort any transaction, or perhaps allow them to commit?!
    for (PrimaryIndex primaryIndex : primaryIndexByName.values()) {
      primaryIndex.close();
    }
    sequenceManager.close();
    for (Store store : storeByPrimaryIndexName.values()) {
      store.close();
    }
  }
}
