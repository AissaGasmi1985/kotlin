/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.deserialization

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirTypeParametersOwner
import org.jetbrains.kotlin.fir.declarations.addDefaultBoundIfNecessary
import org.jetbrains.kotlin.fir.declarations.builder.FirTypeParameterBuilder
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeClassifierLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.*
import org.jetbrains.kotlin.serialization.deserialization.ProtoEnumFlags
import org.jetbrains.kotlin.serialization.deserialization.getClassId
import org.jetbrains.kotlin.serialization.deserialization.getName
import org.jetbrains.kotlin.types.Variance
import java.util.*

class FirTypeDeserializer(
    val session: FirSession,
    val nameResolver: NameResolver,
    val typeTable: TypeTable,
    typeParameterProtos: List<ProtoBuf.TypeParameter>,
    val parent: FirTypeDeserializer?
) {
    private val typeParameterDescriptors: Map<Int, FirTypeParameterSymbol> = if (typeParameterProtos.isNotEmpty()) {
        LinkedHashMap<Int, FirTypeParameterSymbol>()
    } else {
        mapOf()
    }

    private val typeParameterNames: Map<String, FirTypeParameterSymbol>

    val ownTypeParameters: List<FirTypeParameterSymbol>
        get() = typeParameterDescriptors.values.toList()

    init {
        if (typeParameterProtos.isNotEmpty()) {
            typeParameterNames = mutableMapOf()
            val result = typeParameterDescriptors as LinkedHashMap<Int, FirTypeParameterSymbol>
            val builders = mutableListOf<FirTypeParameterBuilder>()
            for (proto in typeParameterProtos) {
                if (!proto.hasId()) continue
                val name = nameResolver.getName(proto.name)
                val symbol = FirTypeParameterSymbol().also {
                    typeParameterNames[name.asString()] = it
                }
                builders += FirTypeParameterBuilder().apply {
                    session = this@FirTypeDeserializer.session
                    this.name = name
                    this.symbol = symbol
                    variance = proto.variance.convertVariance()
                    isReified = proto.reified
                }
                result[proto.id] = symbol
            }

            for ((index, proto) in typeParameterProtos.withIndex()) {
                val builder = builders[index]
                builder.apply {
                    proto.upperBoundList.mapTo(bounds) {
                        buildResolvedTypeRef { type = type(it) }
                    }
                    addDefaultBoundIfNecessary()
                }.build()
            }
        } else {
            typeParameterNames = emptyMap()
        }
    }

    private fun computeClassifier(fqNameIndex: Int): ConeClassLikeLookupTag? {
        try {
            val id = nameResolver.getClassId(fqNameIndex)
            return ConeClassLikeLookupTagImpl(id)
        } catch (e: Throwable) {
            throw RuntimeException("Looking up for ${nameResolver.getClassId(fqNameIndex)}", e)
        }
    }

    fun type(proto: ProtoBuf.Type): ConeKotlinType {
        if (proto.hasFlexibleTypeCapabilitiesId()) {
            val lowerBound = simpleType(proto)
            val upperBound = simpleType(proto.flexibleUpperBound(typeTable)!!)
            return ConeFlexibleType(lowerBound!!, upperBound!!)
            //c.components.flexibleTypeDeserializer.create(proto, id, lowerBound, upperBound)
        }

        return simpleType(proto) ?: ConeKotlinErrorType("?!id:0")
    }

    private fun typeParameterSymbol(typeParameterId: Int): ConeTypeParameterLookupTag? =
        typeParameterDescriptors[typeParameterId]?.toLookupTag() ?: parent?.typeParameterSymbol(typeParameterId)


    private fun ProtoBuf.TypeParameter.Variance.convertVariance(): Variance {
        return when (this) {
            ProtoBuf.TypeParameter.Variance.IN -> Variance.IN_VARIANCE
            ProtoBuf.TypeParameter.Variance.OUT -> Variance.OUT_VARIANCE
            ProtoBuf.TypeParameter.Variance.INV -> Variance.INVARIANT
        }
    }


    fun FirClassLikeSymbol<*>.typeParameters(): List<FirTypeParameterSymbol> =
        (fir as? FirTypeParametersOwner)?.typeParameters?.map { it.symbol }.orEmpty()

    fun simpleType(proto: ProtoBuf.Type): ConeLookupTagBasedType? {

        val constructor = typeSymbol(proto) ?: return null
        if (constructor is ConeTypeParameterLookupTag) return ConeTypeParameterTypeImpl(constructor, isNullable = proto.nullable)
        if (constructor !is ConeClassLikeLookupTag) return null

        fun ProtoBuf.Type.collectAllArguments(): List<ProtoBuf.Type.Argument> =
            argumentList + outerType(typeTable)?.collectAllArguments().orEmpty()

        val arguments = proto.collectAllArguments().map(this::typeArgument).toTypedArray()

        val simpleType = if (Flags.SUSPEND_TYPE.get(proto.flags)) {
            //createSuspendFunctionType(annotations, constructor, arguments, proto.nullable)
            ConeClassErrorType("createSuspendFunctionType not supported")
        } else {
            ConeClassLikeTypeImpl(constructor, arguments, isNullable = proto.nullable)
        }

        val abbreviatedTypeProto = proto.abbreviatedType(typeTable) ?: return simpleType

        return ConeClassLikeTypeImpl(typeSymbol(abbreviatedTypeProto) as ConeClassLikeLookupTag, arguments, isNullable = false)

    }

    private fun typeSymbol(proto: ProtoBuf.Type): ConeClassifierLookupTag? {

        return when {
            proto.hasClassName() -> computeClassifier(proto.className)
            proto.hasTypeAliasName() -> computeClassifier(proto.typeAliasName)
            proto.hasTypeParameter() -> typeParameterSymbol(proto.typeParameter)
            proto.hasTypeParameterName() -> {
                val name = nameResolver.getString(proto.typeParameterName)
                typeParameterNames[name]?.toLookupTag()
            }
            else -> null
        }
    }


    private fun typeArgument(typeArgumentProto: ProtoBuf.Type.Argument): ConeKotlinTypeProjection {
        if (typeArgumentProto.projection == ProtoBuf.Type.Argument.Projection.STAR) {
            return ConeStarProjection
        }

        val variance = ProtoEnumFlags.variance(typeArgumentProto.projection)
        val type = typeArgumentProto.type(typeTable) ?: return ConeKotlinErrorType("No type recorded")

        val coneType = type(type)
        return coneType.toTypeProjection(variance)
    }

}
