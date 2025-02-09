/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators.error

import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.RetryableTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.StdError
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.isOptional
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.errorMessageMember
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.letIf

sealed class ErrorKind {
    abstract fun writable(runtimeConfig: RuntimeConfig): Writable

    object Throttling : ErrorKind() {
        override fun writable(runtimeConfig: RuntimeConfig) =
            writable { rust("#T::ThrottlingError", RuntimeType.errorKind(runtimeConfig)) }
    }

    object Client : ErrorKind() {
        override fun writable(runtimeConfig: RuntimeConfig) =
            writable { rust("#T::ClientError", RuntimeType.errorKind(runtimeConfig)) }
    }

    object Server : ErrorKind() {
        override fun writable(runtimeConfig: RuntimeConfig) =
            writable { rust("#T::ServerError", RuntimeType.errorKind(runtimeConfig)) }
    }
}

/**
 * Returns the modeled retryKind for this shape
 *
 * This is _only_ non-null in cases where the @retryable trait has been applied.
 */
fun StructureShape.modeledRetryKind(errorTrait: ErrorTrait): ErrorKind? {
    val retryableTrait = this.getTrait<RetryableTrait>() ?: return null
    return when {
        retryableTrait.throttling -> ErrorKind.Throttling
        errorTrait.isClientError -> ErrorKind.Client
        errorTrait.isServerError -> ErrorKind.Server
        // The error _must_ be either a client or server error
        else -> TODO()
    }
}

class ErrorGenerator(
    private val symbolProvider: RustSymbolProvider,
    private val writer: RustWriter,
    private val shape: StructureShape,
    private val error: ErrorTrait,
) {
    fun render(forWhom: CodegenTarget = CodegenTarget.CLIENT) {
        val symbol = symbolProvider.toSymbol(shape)
        val messageShape = shape.errorMessageMember()
        val errorKindT = RuntimeType.errorKind(symbolProvider.config().runtimeConfig)
        writer.rustBlock("impl ${symbol.name}") {
            val retryKindWriteable = shape.modeledRetryKind(error)?.writable(symbolProvider.config().runtimeConfig)
            if (retryKindWriteable != null) {
                rust("/// Returns `Some(${errorKindT.name})` if the error is retryable. Otherwise, returns `None`.")
                rustBlock("pub fn retryable_error_kind(&self) -> #T", errorKindT) {
                    retryKindWriteable(this)
                }
            }
            if (messageShape != null) {
                val (returnType, message) = if (symbolProvider.toSymbol(messageShape).isOptional()) {
                    "Option<&str>" to "self.${symbolProvider.toMemberName(messageShape)}.as_deref()"
                } else {
                    "&str" to "self.${symbolProvider.toMemberName(messageShape)}.as_ref()"
                }

                rust(
                    """
                    /// Returns the error message.
                    pub fn message(&self) -> $returnType { $message }
                    """,
                )
            }

            /*
             * If we're generating for a server, the `name` method is added to enable
             * recording encountered error types inside `http::Extensions`s.
             */
            if (forWhom == CodegenTarget.SERVER) {
                rust(
                    """
                    ##[doc(hidden)]
                    /// Returns the error name.
                    pub fn name(&self) -> &'static str {
                        ${shape.id.name.dq()}
                    }
                    """,
                )
            }
        }

        writer.rustBlock("impl #T for ${symbol.name}", RuntimeType.Display) {
            rustBlock("fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result") {
                // If the error id and the Rust name don't match, print the actual error id for easy debugging
                // Note: Exceptions cannot be renamed so it is OK to not call `getName(service)` here
                val errorDesc = symbol.name.letIf(symbol.name != shape.id.name) { symbolName ->
                    "$symbolName [${shape.id.name}]"
                }
                write("write!(f, ${errorDesc.dq()})?;")
                messageShape?.let {
                    ifSet(it, symbolProvider.toSymbol(it), "&self.message") { field ->
                        write("""write!(f, ": {}", $field)?;""")
                    }
                }
                write("Ok(())")
            }
        }
        writer.write("impl #T for ${symbol.name} {}", StdError)
    }
}
