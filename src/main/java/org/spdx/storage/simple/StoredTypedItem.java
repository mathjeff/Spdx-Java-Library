package org.spdx.storage.simple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.SpdxConstants;
import org.spdx.library.model.ModelObject;
import org.spdx.library.model.SpdxInvalidTypeException;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.TypedValue;
import org.spdx.library.model.enumerations.SpdxEnumFactory;
import org.spdx.library.model.IndividualUriValue;
import org.spdx.storage.IModelStore;

/**
 * Individual item to be stored in memory
 * 
 * @author Gary O'Neall
 *
 */
public class StoredTypedItem extends TypedValue {

	static final Logger logger = LoggerFactory.getLogger(TypedValue.class);

	private static final String NO_ID_ID = "__NO_ID__";  // ID to use in list has map for non-typed values
	
	static Set<String> SPDX_CLASSES = new HashSet<>(Arrays.asList(SpdxConstants.ALL_SPDX_CLASSES));

	private ConcurrentHashMap<String, Object> properties = new ConcurrentHashMap<>();
	
	private int referenceCount = 0;
	
	private final ReadWriteLock countLock = new ReentrantReadWriteLock();
	
	public StoredTypedItem(String documentUri, String id, String type) throws InvalidSPDXAnalysisException {
		super(id, type);
	}
	
	/**
	 * @return Property names for all properties having a value
	 */
	public List<String> getPropertyValueNames() {
		Iterator<Entry<String, Object>> iter = this.properties.entrySet().iterator();
		List<String> retval = new ArrayList<>();
		while (iter.hasNext()) {
			Entry<String, Object> entry = iter.next();
			retval.add(entry.getKey());
		}
		return Collections.unmodifiableList(retval);
	}
	
	/**
	 * Increment the reference count for this stored type item - the number of times this item is referenced
	 * @return new number of times this item is referenced
	 */
	public int incReferenceCount() {
	    countLock.writeLock().lock();
	    try {
	        this.referenceCount++;
	        return this.referenceCount;
	    } finally {
	        countLock.writeLock().unlock();
	    }
	}
	
	/**
	 * Decrement the reference count for this stored type item
	 * @return new number of times this item is referenced
	 * @throws SpdxInvalidTypeException
	 */
	public int decReferenceCount() throws SpdxInvalidTypeException {
	       countLock.writeLock().lock();
           try {
               if (this.referenceCount < 1) {
                   throw new SpdxInvalidTypeException("Usage count underflow - usage count decremented more than incremented");
               }
               this.referenceCount--;
               return this.referenceCount;
           } finally {
               countLock.writeLock().unlock();
           }
	}
	
	 /**
     * @return new number of times this item is referenced
     * @throws SpdxInvalidTypeException
     */
    public int getReferenceCount() throws SpdxInvalidTypeException {
           countLock.readLock().lock();
           try {
               return this.referenceCount;
           } finally {
               countLock.readLock().unlock();
           }
    }
	
	/**
	 * @param propertyName Name of the property
	 * @param value Value to be set
	 * @throws SpdxInvalidTypeException 
	 */
	public void setValue(String propertyName, Object value) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(value, "Value can not be null");
		if (value instanceof ModelObject) {
			throw new SpdxInvalidTypeException("Can not store Model Object in store.  Convert to TypedValue first");
		} else if (value instanceof List || value instanceof Collection) {
			throw new SpdxInvalidTypeException("Can not store list values directly.  Use addValueToCollection.");
		} else if (!value.getClass().isPrimitive() && 
				!value.getClass().isAssignableFrom(String.class) &&
				!value.getClass().isAssignableFrom(Boolean.class) &&
				!value.getClass().isAssignableFrom(Integer.class) &&
				!value.getClass().isAssignableFrom(TypedValue.class) &&
				!(value instanceof IndividualUriValue)) {
			throw new SpdxInvalidTypeException(value.getClass().toString()+" is not a supported class to be stored.");
		}
		properties.put(propertyName, value);
	}
	
	/**
	 * Sets the value list for the property to an empty list creating the propertyName if it does not exist
	 * @param propertyName Name of the property
	 * @throws SpdxInvalidTypeException
	 */
	public void clearPropertyValueList(String propertyName) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Object value = properties.get(propertyName);
		if (value == null) {
			return;
		}
		if (value instanceof ConcurrentHashMap<?, ?>) {
			((ConcurrentHashMap<?, ?>)value).clear();
		} else {
			throw new SpdxInvalidTypeException("Trying to clear a list for non list type for property "+propertyName);
		}
		
	}

	/**
	 * Adds a value to a property list for a String or Boolean type of value creating the propertyName if it does not exist
	 * @param propertyName Name of the property
	 * @param value Value to be set
	 * @throws SpdxInvalidTypeException
	 */
	public boolean addValueToList(String propertyName, Object value) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(value, "Value can not be null");
		if (value instanceof ModelObject) {
			throw new SpdxInvalidTypeException("Can not store Model Object in store.  Convert to TypedValue first");
		} else if (!value.getClass().isPrimitive() && 
				!value.getClass().isAssignableFrom(String.class) &&
				!value.getClass().isAssignableFrom(Boolean.class) &&
				!value.getClass().isAssignableFrom(Integer.class) &&
				!value.getClass().isAssignableFrom(TypedValue.class) &&
				!(value instanceof IndividualUriValue)) {
			throw new SpdxInvalidTypeException(value.getClass().toString()+" is not a supported class to be stored.");
		}
		Object map = properties.get(propertyName);
		if (map == null) {
			properties.putIfAbsent(propertyName,  new ConcurrentHashMap<String, List<Object>>());
			map = properties.get(propertyName);	
			//Note: there is a small timing window where the property could be removed
			if (map == null) {
				return true;
			}
		}
		if (!(map instanceof ConcurrentHashMap<?, ?>)) {
			throw new SpdxInvalidTypeException("Trying to add a list for non list type for property "+propertyName);
		}
		try {
			@SuppressWarnings("unchecked")
			ConcurrentHashMap<String, List<Object>> idValueMap = (ConcurrentHashMap<String, List<Object>>)map;
			String id;
			if (value instanceof TypedValue) {
				id = ((TypedValue)value).getId();
			} else {
				id = NO_ID_ID;
			}
			idValueMap.putIfAbsent(id, new ArrayList<Object>());
			List<Object> list = idValueMap.get(id);
			if (list == null) {
				// handle the very small window where this may have gotten removed
				list = new ArrayList<>();
				idValueMap.putIfAbsent(id, list);
			}
			return list.add(value);
		} catch (Exception ex) {
			throw new SpdxInvalidTypeException("Invalid list type for "+propertyName);
		}
	}
	

	public boolean removeTypedValueFromList(String propertyName, TypedValue value) throws SpdxInvalidTypeException {
		Object map = properties.get(propertyName);
		if (map == null) {
			return false;
		}
		if (!(map instanceof ConcurrentHashMap<?, ?>)) {
			throw new SpdxInvalidTypeException("Trying to remove from a list for non typed value list type for property "+propertyName);
		}
		try {
			@SuppressWarnings("unchecked")
			ConcurrentHashMap<String, List<TypedValue>> typedValueMap = (ConcurrentHashMap<String, List<TypedValue>>)map;
			List<TypedValue> list = typedValueMap.get(value.getId());
			if (list == null) {
				return false;
			}
			return list.remove(value);
		} catch (Exception ex) {
			throw new SpdxInvalidTypeException("Invalid list type for "+propertyName);
		}
	}

	/**
	 * Removes a property from a list if it exists
	 * @param propertyName
	 * @param value
	 * @throws SpdxInvalidTypeException 
	 */
	public boolean removeValueFromList(String propertyName, Object value) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(value, "Value can not be null");
		Object map = properties.get(propertyName);
		if (map == null) {
			return false;
		}
		if (!(map instanceof ConcurrentHashMap<?, ?>)) {
			throw new SpdxInvalidTypeException("Trying to remove from a list for non list type for property "+propertyName);
		}
		try {
			@SuppressWarnings("unchecked")
			ConcurrentHashMap<String, List<Object>> idValueMap = (ConcurrentHashMap<String, List<Object>>)map;
			String id;
			if (value instanceof TypedValue) {
				id = ((TypedValue)value).getId();
			} else {
				id = NO_ID_ID;
			}
			List<Object> list = idValueMap.get(id);
			if (list == null) {
				return false;
			}
			return list.remove(value);
		} catch (Exception ex) {
			throw new SpdxInvalidTypeException("Invalid list type for "+propertyName);
		}
	}
	
	/**
	 * @param propertyName Name of the property
	 * @return List of values associated with the id, propertyName and document
	 * @throws SpdxInvalidTypeException
	 */
	public Iterator<Object> getValueList(String propertyName) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Object list = properties.get(propertyName);
		if (list == null) {
			return Collections.emptyIterator();
		}
		if (list instanceof ConcurrentHashMap<?, ?>) {
			List<Object> valueList = new ArrayList<>();
			for (Object value : ((ConcurrentHashMap<?, ?>) list).values() ) {
				if (value instanceof Collection) {
					valueList.addAll((Collection<?>)value);
				} else {
					valueList.add(value);
				}
			}
			return valueList.iterator();
		} else {
			throw new SpdxInvalidTypeException("Trying to get a list for non list type for property "+propertyName);
		}
	}
	
	/**
	 * @param propertyName Name of the property
	 * @return the single value associated with the id, propertyName and document
	 */
	public Object getValue(String propertyName) {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		return properties.get(propertyName);
	}
	
	/**
	 * Removes a property from the document for the given ID if the property exists.  Does not raise any exception if the propertyName does not exist
	 * @param propertyName Name of the property
	 */
	public void removeProperty(String propertyName) {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		properties.remove(propertyName);
	}

	/**
	 * Copy all values for this item from another store
	 * @param fromDocumentUri 
	 * @param store
	 * @throws InvalidSPDXAnalysisException 
	 */
	public void copyValuesFrom(String fromDocumentUri, IModelStore store) throws InvalidSPDXAnalysisException {
		Objects.requireNonNull(fromDocumentUri, "From document URI can not be null");
		Objects.requireNonNull(store, "Store can not be null");
		List<String> propertyNames = store.getPropertyValueNames(fromDocumentUri, this.getId());
		for (String propertyName:propertyNames) {
			Optional<Object> value = store.getValue(fromDocumentUri, getId(), propertyName);
			if (value.isPresent()) {
				this.setValue(propertyName, value.get());
			}
		}
	}

	/**
	 * @param propertyName
	 * @return Size of the collection
	 * @throws SpdxInvalidTypeException 
	 */
	@SuppressWarnings("rawtypes")
	public int collectionSize(String propertyName) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Object map = properties.get(propertyName);
		if (map == null) {
			properties.putIfAbsent(propertyName,  new ConcurrentHashMap<String, List<Object>>());
			map = properties.get(propertyName);	
			//Note: there is a small timing window where the property could be removed
			if (map == null) {
				return 0;
			}
		}
		if (map instanceof ConcurrentHashMap<?, ?>) {
			int count = 0;
			for (Object value:((ConcurrentHashMap<?, ?>)map).values()) {
				if (value instanceof Collection) {
					count = count + ((Collection)value).size();
				} else {
					count++;
				}
			}
			return count;
		} else {
			throw new SpdxInvalidTypeException("Trying to get size for a non list type for property "+propertyName);
		}
	}

	/**
	 * @param propertyName property name
	 * @param value value to be checked
	 * @return true if value is in the list associated with the property name
	 * @throws SpdxInvalidTypeException
	 */
	public boolean collectionContains(String propertyName, Object value) throws SpdxInvalidTypeException {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(value, "Value can not be null");
		Object map = properties.get(propertyName);
		if (map == null) {
			properties.putIfAbsent(propertyName,  new ConcurrentHashMap<String, List<Object>>());
			map = properties.get(propertyName);	
			//Note: there is a small timing window where the property could be removed
			if (map == null) {
				return false;
			}
		}
		if (map instanceof ConcurrentHashMap<?, ?>) {
			String id;
			if (value instanceof TypedValue) {
				id = ((TypedValue)value).getId();
			} else {
				id = NO_ID_ID;
			}
			@SuppressWarnings("unchecked")
			ConcurrentHashMap<String, List<Object>> typedValueMap = (ConcurrentHashMap<String, List<Object>>)map;
			List<Object> valueList = typedValueMap.get(id);
			if (valueList == null) {
				return false;
			}
			return valueList.contains(value);
		} else {
			throw new SpdxInvalidTypeException("Trying to find contains for non list type for property "+propertyName);
		}
	}

	public boolean isCollectionMembersAssignableTo(String propertyName, Class<?> clazz) {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(clazz, "Class can not be null");
		Object map = properties.get(propertyName);
		if (map == null) {
			return true; // It is still assignable to since it is unassigned
		}
		if (!(map instanceof ConcurrentHashMap<?,?>)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		ConcurrentHashMap<String, List<Object>> idValueMap = (ConcurrentHashMap<String, List<Object>>)map;
		for (List<Object> valueList:idValueMap.values()) {
			for (Object value:valueList) {
				if (!clazz.isAssignableFrom(value.getClass())) {
					if (value instanceof IndividualUriValue) {
						String uri = ((IndividualUriValue)value).getIndividualURI();
						Enum<?> spdxEnum = SpdxEnumFactory.uriToEnum.get(uri);
						if (Objects.nonNull(spdxEnum)) {
							if (!clazz.isAssignableFrom(spdxEnum.getClass())) {
								return false;
							}
						} else if (!(SpdxConstants.URI_VALUE_NOASSERTION.equals(uri) ||
								SpdxConstants.URI_VALUE_NONE.equals(uri))) {
							return false;
						}
					} else if (value instanceof TypedValue) {
						try {
							if (clazz != TypedValue.class && !clazz.isAssignableFrom(SpdxModelFactory.typeToClass(((TypedValue)value).getType()))) {
								return false;
							}
						} catch (InvalidSPDXAnalysisException e) {
							logger.error("Error converting typed value to class",e);
							return false;
						}
					} else {
						return false;
					}
				}
			}
		}
		return true;
	}

	public boolean isPropertyValueAssignableTo(String propertyName, Class<?> clazz) {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Objects.requireNonNull(clazz, "Class can not be null");
		Object value = properties.get(propertyName);
		if (value == null) {
			return false;
		}
		if (clazz.isAssignableFrom(value.getClass())) {
			return true;
		}
		if (value instanceof TypedValue) {
			try {
				return clazz.isAssignableFrom(SpdxModelFactory.typeToClass(((TypedValue)value).getType()));
			} catch (InvalidSPDXAnalysisException e) {
				logger.error("Error converting typed value to class",e);
				return false;
			}
		}
		if (value instanceof IndividualUriValue) {
			String uri = ((IndividualUriValue)value).getIndividualURI();
			if (SpdxConstants.URI_VALUE_NOASSERTION.equals(uri)) {
				return true;
			}
			if (SpdxConstants.URI_VALUE_NONE.equals(uri)) {
				return true;
			}
			Enum<?> spdxEnum = SpdxEnumFactory.uriToEnum.get(uri);
			if (Objects.nonNull(spdxEnum)) {
				return clazz.isAssignableFrom(spdxEnum.getClass());
			} else {
				return false;
			}
		}
		return false;
	}

	/**
	 * @param propertyName property name
	 * @return true if there is a list associated with the property name
	 */
	public boolean isCollectionProperty(String propertyName) {
		Objects.requireNonNull(propertyName, "Property name can not be null");
		Object value = properties.get(propertyName);
		return value instanceof ConcurrentHashMap;
	}

	/**
	 * @param elementId id for the element to check
	 * @return true if an element using the id is used as a value in a collection
	 */
	public boolean usesId(String elementId) {
		if (Objects.isNull(elementId)) {
			return false;
		}
		Iterator<Object> allValues = this.properties.values().iterator();
		while (allValues.hasNext()) {
			Object value = allValues.next();
			if (value instanceof List && ((List<?>)value).size() > 0 && ((List<?>)value).get(0) instanceof TypedValue) {
				for (Object listValue:(List<?>)value) {
					if (listValue instanceof TypedValue && ((TypedValue) listValue).getId().toLowerCase().equals(elementId.toLowerCase())) {
						return true;
					}
				}
			} else if (value instanceof TypedValue) {
				if (((TypedValue)value).getId().toLowerCase().equals(elementId.toLowerCase())) {
					return true;
				}
			}
		}
		return false;
	}
}