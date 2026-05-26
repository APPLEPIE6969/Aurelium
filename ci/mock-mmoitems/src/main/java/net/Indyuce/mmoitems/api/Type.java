package net.Indyuce.mmoitems.api;

public class Type {

 private final String id;

 public Type(String id) {
 this.id = id;
 }

 public String getId() {
 return id;
 }

 @Override
 public String toString() {
 return id;
 }
}
