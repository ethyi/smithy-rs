/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

use std::error::Error;

/// Diagnostic collector for endpoint resolution
///
/// Endpoint functions return `Option<T>`—to enable diagnostic information to flow, we capture the
/// last error that occurred.
#[derive(Debug, Default)]
pub(crate) struct DiagnosticCollector {
    last_error: Option<Box<dyn Error + Send + Sync>>,
}

impl DiagnosticCollector {
    /// Report an error to the collector
    pub(crate) fn report_error(&mut self, err: impl Into<Box<dyn Error + Send + Sync>>) {
        self.last_error = Some(err.into());
    }

    /// Capture a result, returning Some(t) when the input was `Ok` and `None` otherwise
    pub(crate) fn capture<T, E: Into<Box<dyn Error + Send + Sync>>>(
        &mut self,
        err: Result<T, E>,
    ) -> Option<T> {
        match err {
            Ok(res) => Some(res),
            Err(e) => {
                self.report_error(e);
                None
            }
        }
    }

    pub(crate) fn take_last_error(&mut self) -> Option<Box<dyn Error + Send + Sync>> {
        self.last_error.take()
    }

    /// Create a new diagnostic collector
    pub(crate) fn new() -> Self {
        Self { last_error: None }
    }
}
