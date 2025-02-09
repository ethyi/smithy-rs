/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy.generators

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.asType
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.ServerCombinedErrorGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.errorSymbol
import software.amazon.smithy.rust.codegen.server.python.smithy.PythonServerCargoDependency

/**
 * Generates a unified error enum for [operation]. It depends on [ServerCombinedErrorGenerator]
 * to generate the errors from the model and adds the Rust implementation `From<pyo3::PyErr>`.
 */
class PythonServerCombinedErrorGenerator(
    private val model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val operation: OperationShape,
) : ServerCombinedErrorGenerator(model, symbolProvider, symbolProvider.toSymbol(operation), listOf()) {

    private val operationIndex = OperationIndex.of(model)
    private val errors = operationIndex.getErrors(operation)

    override fun render(writer: RustWriter) {
        super.render(writer)
        renderFromPyErr(writer)
    }

    private fun renderFromPyErr(writer: RustWriter) {
        writer.rustTemplate(
            """
            impl #{From}<#{pyo3}::PyErr> for #{Error} {
                fn from(variant: #{pyo3}::PyErr) -> #{Error} {
                    #{pyo3}::Python::with_gil(|py|{
                        let error = variant.value(py);
                        #{CastPyErrToRustError:W}
                        crate::error::InternalServerError { message: error.to_string() }.into()
                    })
                }
            }

            """,
            "pyo3" to PythonServerCargoDependency.PyO3.asType(),
            "Error" to operation.errorSymbol(model, symbolProvider, CodegenTarget.SERVER),
            "From" to RuntimeType.From,
            "CastPyErrToRustError" to castPyErrToRustError(),
        )
    }

    private fun castPyErrToRustError(): Writable =
        writable {
            errors.forEach { error ->
                val errorSymbol = symbolProvider.toSymbol(error)
                if (errorSymbol.toString() != "crate::error::InternalServerError") {
                    rust(
                        """
                        if let Ok(error) = error.extract::<$errorSymbol>() {
                            return error.into()
                        }
                        """,
                    )
                }
            }
        }
}
