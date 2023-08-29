package io.ebeaninternal.server.transaction;

import io.ebeaninternal.server.cache.CacheChangeSet;
import io.ebeaninternal.server.core.PersistRequest;
import io.ebeaninternal.server.deploy.BeanDescriptor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Beans deleted by Id used for updating L2 Cache.
 */
public final class DeleteByIdMap {

  private final Map<String, BeanPersistIds> beanMap = new LinkedHashMap<>();

  @Override
  public String toString() {
    return "DeleteById " + beanMap.values();
  }

  public void notifyCache(CacheChangeSet changeSet) {
    for (BeanPersistIds deleteIds : beanMap.values()) {
      BeanDescriptor<?> d = deleteIds.getBeanDescriptor();
      List<Object> idValues = deleteIds.getIds();
      if (idValues != null) {
        d.cachePersistDeleteByIds(idValues, changeSet);
      }
    }
  }

  public boolean isEmpty() {
    return beanMap.isEmpty();
  }

  public Collection<BeanPersistIds> values() {
    return beanMap.values();
  }

  /**
   * Add a Insert Update or Delete payload.
   */
  public void add(BeanDescriptor<?> desc, Object id) {

    BeanPersistIds r = getPersistIds(desc);
    r.addId(PersistRequest.Type.DELETE, id);
  }

  /**
   * Add a List of Insert Update or Delete Id's.
   */
  public void addList(BeanDescriptor<?> desc, List<Object> idList) {

    BeanPersistIds r = getPersistIds(desc);
    for (Object idValue : idList) {
      r.addId(PersistRequest.Type.DELETE, idValue);
    }
  }

  private BeanPersistIds getPersistIds(BeanDescriptor<?> desc) {
    String beanType = desc.fullName();
    return beanMap.computeIfAbsent(beanType, k -> new BeanPersistIds(desc));
  }

}
