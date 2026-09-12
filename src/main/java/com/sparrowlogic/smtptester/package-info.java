/**
 * SMTP testing server: receives mail on 1025, serves an inbox, a REST API and an MCP server on 8025.
 *
 * <p>Every package in this project is {@link org.jspecify.annotations.NullMarked}, so NullAway
 * enforces null-safety across the whole codebase rather than opting in package by package.
 */
@NullMarked
package com.sparrowlogic.smtptester;

import org.jspecify.annotations.NullMarked;
