package io.ra6.zephyr.codeanalysis.binding;

public enum BoundNodeKind {
    IF_STATEMENT,
    GOTO_STATEMENT,
    BLOCK_STATEMENT,
    CONDITIONAL_GOTO_STATEMENT,
    LABEL_STATEMENT,
    WHILE_STATEMENT,
    RETURN_STATEMENT,
    VARIABLE_DECLARATION,
    EXPRESSION_STATEMENT,

    ERROR_EXPRESSION,
    LITERAL_EXPRESSION,
    BINARY_EXPRESSION,
    CONDITIONAL_EXPRESSION,
    INSTANCE_CREATION_EXPRESSION,
    VARIABLE_EXPRESSION,
    ASSIGNMENT_EXPRESSION,
    FIELD_ACCESS_EXPRESSION,
    MEMBER_ACCESS_EXPRESSION,
    FUNCTION_CALL_EXPRESSION,
    UNARY_EXPRESSION,
    THIS_EXPRESSION,
    TYPE_EXPRESSION,
    INTERNAL_FUNCTION_EXPRESSION,
    ARRAY_LITERAL_EXPRESSION,
    ARRAY_ACCESS_EXPRESSION,
    ARRAY_CREATION_EXPRESSION,
    CONVERSION_EXPRESSION, TYPE_CHECK_EXPRESSION,
}
