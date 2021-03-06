/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.tools.bootImageWriter.types;

import static org.jikesrvm.tools.bootImageWriter.BootImageWriterMessages.say;
import static org.jikesrvm.tools.bootImageWriter.Verbosity.SUMMARY;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;

import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.tools.bootImageWriter.BootImageWriter;

/**
 * Manages information about the mapping between host JDK and boot image types.
 */
public abstract class BootImageTypes {

  /**
   * Types to be placed into bootimage, stored as key/value pairs
   * where key is a String like "java.lang.Object" or "[Ljava.lang.Object;"
   * and value is the corresponding RVMType.
   */
  private static final Hashtable<String,RVMType> bootImageTypes =
    new Hashtable<String,RVMType>(5000);

  /**
   * For all the scalar types to be placed into bootimage, keep
   * key/value pairs where key is a Key(jdkType) and value is
   * a FieldInfo.
   */
  private static HashMap<Key,FieldInfo> bootImageTypeFields;

  public static void record(String typeName, RVMType type) {
    bootImageTypes.put(typeName, type);
  }

  public static int typeCount() {
    return bootImageTypes.size();
  }

  public static Collection<RVMType> allTypes() {
    return bootImageTypes.values();
  }

  /**
   * Obtains RVM type corresponding to host JDK type.
   *
   * @param jdkType JDK type
   * @return RVM type ({@code null} --> type does not appear in list of classes
   *         comprising bootimage)
   */
  public static RVMType getRvmTypeForHostType(Class<?> jdkType) {
    return bootImageTypes.get(jdkType.getName());
  }

  public static HashSet<String> createBootImageTypeFields() {
    int typeCount = typeCount();
    BootImageTypes.bootImageTypeFields = new HashMap<Key,FieldInfo>(typeCount);
    HashSet<String> invalidEntrys = new HashSet<String>();

    // First retrieve the jdk Field table for each class of interest
    for (RVMType rvmType : allTypes()) {
      FieldInfo fieldInfo;
      if (!rvmType.isClassType())
        continue; // arrays and primitives have no static or instance fields

      Class<?> jdkType = BootImageTypes.getJdkType(rvmType);
      if (jdkType == null)
        continue;  // won't need the field info

      Key key   = new Key(jdkType);
      fieldInfo = BootImageTypes.bootImageTypeFields.get(key);
      if (fieldInfo != null) {
        fieldInfo.rvmType = rvmType;
      } else {
        if (BootImageWriter.verbosity().isAtLeast(SUMMARY)) say("making fieldinfo for " + rvmType);
        fieldInfo = new FieldInfo(jdkType, rvmType);
        BootImageTypes.bootImageTypeFields.put(key, fieldInfo);
        // Now do all the superclasses if they don't already exist
        // Can't add them in next loop as Iterator's don't allow updates to collection
        for (Class<?> cls = jdkType.getSuperclass(); cls != null; cls = cls.getSuperclass()) {
          key = new Key(cls);
          fieldInfo = BootImageTypes.bootImageTypeFields.get(key);
          if (fieldInfo != null) {
            break;
          } else {
            if (BootImageWriter.verbosity().isAtLeast(SUMMARY)) say("making fieldinfo for " + jdkType);
            fieldInfo = new FieldInfo(cls, null);
            BootImageTypes.bootImageTypeFields.put(key, fieldInfo);
          }
        }
      }
    }
    // Now build the one-to-one instance and static field maps
    for (FieldInfo fieldInfo : BootImageTypes.bootImageTypeFields.values()) {
      RVMType rvmType = fieldInfo.rvmType;
      if (rvmType == null) {
        if (BootImageWriter.verbosity().isAtLeast(SUMMARY)) say("bootImageTypeField entry has no rvmType:" + fieldInfo.jdkType);
        continue;
      }
      Class<?> jdkType   = fieldInfo.jdkType;
      if (BootImageWriter.verbosity().isAtLeast(SUMMARY)) say("building static and instance fieldinfo for " + rvmType);

      // First the static fields
      //
      RVMField[] rvmFields = rvmType.getStaticFields();
      fieldInfo.jdkStaticFields = new Field[rvmFields.length];

      for (int j = 0; j < rvmFields.length; j++) {
        String  rvmName = rvmFields[j].getName().toString();
        for (Field f : fieldInfo.jdkFields) {
          if (f.getName().equals(rvmName)) {
            fieldInfo.jdkStaticFields[j] = f;
            f.setAccessible(true);
            break;
          }
        }
      }

      // Now the instance fields
      //
      rvmFields = rvmType.getInstanceFields();
      fieldInfo.jdkInstanceFields = new Field[rvmFields.length];

      for (int j = 0; j < rvmFields.length; j++) {
        String  rvmName = rvmFields[j].getName().toString();
        // We look only in the JDK type that corresponds to the
        // RVMType of the field's declaring class.
        // This is the only way to correctly handle private fields.
        jdkType = BootImageTypes.getJdkType(rvmFields[j].getDeclaringClass());
        if (jdkType == null) continue;
        FieldInfo jdkFieldInfo = BootImageTypes.bootImageTypeFields.get(new Key(jdkType));
        if (jdkFieldInfo == null) continue;
        Field[] jdkFields = jdkFieldInfo.jdkFields;
        for (Field f : jdkFields) {
          if (f.getName().equals(rvmName)) {
            fieldInfo.jdkInstanceFields[j] = f;
            f.setAccessible(true);
            break;
          }
        }
      }
    }
    return invalidEntrys;
  }



  /**
   * Obtains accessor via which a field value may be fetched from host JDK
   * address space.
   *
   * @param jdkType class whose field is sought
   * @param index index in FieldInfo of field sought
   * @param isStatic is field from Static field table, indicates which table to consult
   * @return field accessor (null --> host class does not have specified field)
   */
  public static Field getJdkFieldAccessor(Class<?> jdkType, int index, boolean isStatic) {
    FieldInfo fInfo = bootImageTypeFields.get(new Key(jdkType));
    Field     f;
    if (isStatic == BootImageWriter.STATIC_FIELD) {
      f = fInfo.jdkStaticFields[index];
      return f;
    } else {
      f = fInfo.jdkInstanceFields[index];
      return f;
    }
  }

  /**
   * Obtains host JDK type corresponding to target RVM type.
   *
   * @param rvmType RVM type
   * @return JDK type ({@code null} --> type does not exist in host namespace)
   */
  public static Class<?> getJdkType(RVMType rvmType) {
    Throwable x;
    try {
      return Class.forName(rvmType.toString());
    } catch (ExceptionInInitializerError e) {
      throw e;
    } catch (IllegalAccessError e) {
      x = e;
    } catch (UnsatisfiedLinkError e) {
      x = e;
    } catch (NoClassDefFoundError e) {
      x = e;
    } catch (SecurityException e) {
      x = e;
    } catch (ClassNotFoundException e) {
      x = e;
    }
    if (BootImageWriter.verbosity().isAtLeast(SUMMARY)) {
      say(x.toString());
    }
    return null;
  }

}
