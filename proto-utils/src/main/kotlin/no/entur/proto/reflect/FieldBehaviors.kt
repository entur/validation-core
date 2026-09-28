package no.entur.proto.reflect

import com.google.api.FieldBehavior
import com.google.api.FieldBehaviorProto
import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.FieldDescriptor

// Reflection helpers over the `google.api.field_behavior` extension.

/** Whether this field has the given behavior annotation */
fun FieldDescriptor.hasBehavior(behavior: FieldBehavior): Boolean = behavior in options.getExtension(FieldBehaviorProto.fieldBehavior)

/** Every field of this message type declaring [behavior] (not recursive - top-level fields only). */
fun Descriptor.fieldsWithBehavior(behavior: FieldBehavior): Set<FieldDescriptor> = fields.filter { it.hasBehavior(behavior) }.toSet()
