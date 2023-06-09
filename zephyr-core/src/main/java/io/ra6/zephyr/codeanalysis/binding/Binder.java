package io.ra6.zephyr.codeanalysis.binding;

import io.ra6.zephyr.Triple;
import io.ra6.zephyr.Tuple;
import io.ra6.zephyr.builtin.Natives;
import io.ra6.zephyr.builtin.Types;
import io.ra6.zephyr.builtin.natives.NativeType;
import io.ra6.zephyr.builtin.types.BuiltinType;
import io.ra6.zephyr.codeanalysis.binding.expressions.*;
import io.ra6.zephyr.codeanalysis.binding.scopes.BoundProgramScope;
import io.ra6.zephyr.codeanalysis.binding.scopes.BoundScope;
import io.ra6.zephyr.codeanalysis.binding.scopes.BoundScopeKind;
import io.ra6.zephyr.codeanalysis.binding.scopes.BoundTypeScope;
import io.ra6.zephyr.codeanalysis.binding.statements.*;
import io.ra6.zephyr.codeanalysis.lowering.Lowerer;
import io.ra6.zephyr.codeanalysis.symbols.*;
import io.ra6.zephyr.codeanalysis.syntax.*;
import io.ra6.zephyr.codeanalysis.syntax.expressions.*;
import io.ra6.zephyr.codeanalysis.syntax.statements.*;
import io.ra6.zephyr.diagnostic.DiagnosticBag;
import io.ra6.zephyr.library.ZephyrLibrary;
import io.ra6.zephyr.sourcefile.SourceText;
import io.ra6.zephyr.sourcefile.TextLocation;
import lombok.Getter;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

// TODO: When importing a file from a library, that has already been imported somewhere else, we should not re-parse/bind it.

public class Binder {
    @Getter
    private final DiagnosticBag diagnostics = new DiagnosticBag();

    private final SyntaxTree syntaxTree;
    private final ZephyrLibrary standardLibrary;

    private final BoundProgramScope programScope;
    private BoundScope scope;

    private BinaryOperatorSymbol currentBinaryOperator;
    private UnaryOperatorSymbol currentUnaryOperator;
    private CallableSymbol currentFunctionOrConstructor;
    private TypeSymbol currentType;

    private int labelCounter = 0;
    private final Stack<Tuple<BoundLabel, BoundLabel>> loopStack = new Stack<>();

    public Binder(SyntaxTree syntaxTree, ZephyrLibrary standardLibrary) {
        this.syntaxTree = syntaxTree;
        this.standardLibrary = standardLibrary;

        this.diagnostics.addAll(syntaxTree.getDiagnostics());

        this.programScope = new BoundProgramScope(syntaxTree.getSourceText().getFilePath());

        // TODO: declare builtin types#
        for (BuiltinType type : Types.getBuiltinTypes()) {
            type.declareAll();
            programScope.declareType(type.getTypeSymbol());
        }

        for (BuiltinType type : Types.getBuiltinTypes()) {
            type.defineAll();
            programScope.defineType(type.getTypeSymbol(), type.getTypeScope());
        }

        this.scope = programScope;
    }

    public BoundProgram bindProgram() {
        CompilationUnitSyntax root = syntaxTree.getRoot();

        for (StatementSyntax statement : root.getStatements()) {
            switch (statement.getKind()) {
                case IMPORT_DECLARATION -> bindImportDeclaration((ImportDeclarationSyntax) statement);
                case TYPE_DECLARATION -> declareTypeDeclaration((TypeDeclarationSyntax) statement);
                case NATIVE_TYPE_DECLARATION -> declareNativeTypeDeclaration((NativeTypeDeclarationSyntax) statement);
                case EXPORT_DECLARATION -> bindExportDeclaration((ExportDeclarationSyntax) statement);
                default -> {
                    return new BoundProgram(programScope, diagnostics);
                }
            }
        }

        for (StatementSyntax statement : root.getStatements()) {
            if (Objects.requireNonNull(statement.getKind()) == SyntaxKind.TYPE_DECLARATION) {
                bindTypeDeclaration((TypeDeclarationSyntax) statement);
            }
        }

        if (scope.getKind() != BoundScopeKind.PROGRAM) {
            throw new RuntimeException("Unexpected scope kind: " + scope.getKind());
        }

        return new BoundProgram(programScope, diagnostics);
    }

    private void declareNativeTypeDeclaration(NativeTypeDeclarationSyntax syntax) {
        String name = syntax.getIdentifier().getText();

        if (programScope.isTypeDeclared(name)) {
            diagnostics.reportTypeAlreadyDeclared(syntax.getIdentifier().getLocation(), name);
            return;
        }

        NativeType nativeType = Natives.getNativeType(name);

        nativeType.declareAll();
        programScope.declareType(nativeType.getTypeSymbol());

        nativeType.defineAll();
        programScope.defineType(nativeType.getTypeSymbol(), nativeType.getTypeScope());
    }


    private void bindExportDeclaration(ExportDeclarationSyntax syntax) {
        String name = syntax.getQualifiedName().getText();

        if (!programScope.isTypeDeclared(name)) {
            diagnostics.reportUndefinedType(syntax.getQualifiedName().getLocation(), name);
            return;
        }

        // check if type is imported

        TypeSymbol type = programScope.getType(name);
        ExportSymbol export = new ExportSymbol(type);

        if (programScope.isExportDeclared(name)) {
            diagnostics.reportExportAlreadyDeclared(syntax.getQualifiedName().getLocation(), name);
            return;
        }

        programScope.declareExport(export);
    }

    private void declareTypeDeclaration(TypeDeclarationSyntax syntax) {
        String typeName = syntax.getIdentifier().getText();

        if (programScope.isTypeDeclared(typeName)) {
            diagnostics.reportTypeAlreadyDeclared(syntax.getIdentifier().getLocation(), typeName);
            return;
        }

        if (isReservedTypeName(typeName)) {
            diagnostics.reportReservedTypeName(syntax.getIdentifier().getLocation(), typeName);
            return;
        }

        boolean hasGenerics = syntax.getGenericParameterClause() != null;

        TypeSymbol type = new TypeSymbol(typeName);
        programScope.declareType(type);

        BoundTypeScope typeScope = new BoundTypeScope(scope, type);
        scope = typeScope;

        if (hasGenerics) {
            for (GenericParameterSyntax genericParameter : syntax.getGenericParameterClause().getGenericParameters()) {
                String genericName = genericParameter.getIdentifier().getText();

                if (typeScope.isGenericDeclared(genericName)) {
                    diagnostics.reportGenericAlreadyDeclared(genericParameter.getIdentifier().getLocation(), genericName);
                    continue;
                }

                typeScope.declareGeneric(genericName);
            }
        }

        // Declare first
        for (StatementSyntax member : syntax.getMembers()) {
            switch (member.getKind()) {
                case TYPE_FIELD_DECLARATION -> declareTypeFieldDeclaration((TypeFieldDeclarationSyntax) member);
                case TYPE_FUNCTION_DECLARATION ->
                        declareTypeFunctionDeclaration((TypeFunctionDeclarationSyntax) member);
                case TYPE_BINARY_OPERATOR_DECLARATION ->
                        declareTypeBinaryOperatorDeclaration((TypeBinaryOperatorDeclarationSyntax) member);
                case TYPE_CONSTRUCTOR_DECLARATION ->
                        declareTypeConstructorDeclaration((TypeConstructorDeclarationSyntax) member);
                case TYPE_UNARY_OPERATOR_DECLARATION ->
                        declareTypeUnaryOperatorDeclaration((TypeUnaryOperatorDeclarationSyntax) member);
            }
        }


        declareGeneratedFunctions(syntax, typeScope);

        type.setFieldsAndFunctions(typeScope.getDeclaredFieldsAndFunctions());
        type.setConstructors(typeScope.getDeclaredConstructors());
        type.setBinaryOperators(typeScope.getDeclaredBinaryOperators());
        type.setUnaryOperators(typeScope.getDeclaredUnaryOperators());
        type.setGenericTypes(typeScope.getDeclaredGenericTypes());

        scope = scope.getParent();
        programScope.defineType(type, typeScope);
        currentType = null;
    }

    private void bindTypeDeclaration(TypeDeclarationSyntax syntax) {
        String typeName = syntax.getIdentifier().getText();

        if (isReservedTypeName(typeName)) {
            // Don't report this error again, because it was already reported in declareTypeDeclaration
            return;
        }

        if (!programScope.isTypeDeclared(typeName)) {
            diagnostics.reportUndefinedType(syntax.getIdentifier().getLocation(), typeName);
            return;
        }

        TypeSymbol type = programScope.getType(typeName);
        currentType = type;

        BoundTypeScope typeScope = programScope.getTypeScope(type);
        scope = typeScope;

        for (StatementSyntax member : syntax.getMembers()) {
            switch (member.getKind()) {
                case TYPE_FIELD_DECLARATION -> bindTypeFieldDeclaration((TypeFieldDeclarationSyntax) member);
                case TYPE_FUNCTION_DECLARATION -> bindTypeFunctionDeclaration((TypeFunctionDeclarationSyntax) member);
                case TYPE_CONSTRUCTOR_DECLARATION ->
                        bindTypeConstructorDeclaration((TypeConstructorDeclarationSyntax) member);
                case TYPE_BINARY_OPERATOR_DECLARATION ->
                        bindTypeBinaryOperatorDeclaration((TypeBinaryOperatorDeclarationSyntax) member);
                case TYPE_UNARY_OPERATOR_DECLARATION ->
                        bindTypeUnaryOperatorDeclaration((TypeUnaryOperatorDeclarationSyntax) member);
                default ->
                        throw new RuntimeException(member.getLocation() + " Unexpected statement kind: " + member.getKind());
            }
        }

        defineGeneratedFunctions(type, typeScope);

        scope = scope.getParent();
        currentType = null;
    }

    private void declareGeneratedFunctions(TypeDeclarationSyntax syntax, BoundTypeScope typeScope) {
        FunctionSymbol toStringFunction = typeScope.getFunction("toString");
        FunctionSymbol equalsFunction = typeScope.getFunction("equals");

        if (toStringFunction == null) {
            toStringFunction = new FunctionSymbol("toString", false, Visibility.PUBLIC, List.of(), Types.STRING);
        } else {
            if (toStringFunction.getParameters().size() != 0) {
                diagnostics.reportInvalidToStringFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Parameter count must be 0");
                return;
            }

            if (!toStringFunction.getType().equals(Types.STRING)) {
                diagnostics.reportInvalidToStringFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Return type must be string");
                return;
            }

            if (toStringFunction.getVisibility() != Visibility.PUBLIC) {
                diagnostics.reportInvalidToStringFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Visibility must be public");
                return;
            }
        }

        if (equalsFunction == null) {
            equalsFunction = new FunctionSymbol("equals", false, Visibility.PUBLIC, List.of(new ParameterSymbol("other", Types.ANY)), Types.BOOL);
        } else {
            if (equalsFunction.getParameters().size() != 1) {
                diagnostics.reportInvalidEqualsFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Parameter count must be 1");
                return;
            }

            if (!equalsFunction.getType().equals(Types.BOOL)) {
                diagnostics.reportInvalidEqualsFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Return type must be boolean");
                return;
            }

            if (equalsFunction.getVisibility() != Visibility.PUBLIC) {
                diagnostics.reportInvalidEqualsFunction(syntax.getLocation(), syntax.getIdentifier().getText(), "Visibility must be public");
                return;
            }
        }

        typeScope.declareFunction(equalsFunction);
        typeScope.declareFunction(toStringFunction);
    }

    private void defineGeneratedFunctions(TypeSymbol type, BoundTypeScope typeScope) {
        FunctionSymbol toStringFunction = typeScope.getFunction("toString");
        FunctionSymbol equalsFunction = typeScope.getFunction("equals");

        if (typeScope.getFunctionBody(toStringFunction) == null) {
            BoundBlockStatement body = BoundNodeFactory.createBlockStatement(null, new BoundReturnStatement(null, new BoundLiteralExpression(null, type.getName(), Types.STRING)));
            typeScope.defineFunction(toStringFunction, body);
        }

        if (typeScope.getFunctionBody(equalsFunction) == null) {
            // TODO: generate equals function. Should contain all non shared fields
            BoundBlockStatement body = generateEqualsFunction(type);
            typeScope.defineFunction(equalsFunction, body);
        }
    }

    private BoundBlockStatement generateEqualsFunction(TypeSymbol type) {
        BoundExpression other = new BoundVariableExpression(null, new VariableSymbol("other", true, Types.ANY));

        BoundReturnStatement returnStatement = new BoundReturnStatement(null, BoundNodeFactory.createTypeEqualsExpression(null, new BoundThisExpression(null, type), other.getType()));

        return BoundNodeFactory.createBlockStatement(null, returnStatement);
    }

    private void declareTypeFieldDeclaration(TypeFieldDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;

        String fieldName = syntax.getIdentifier().getText();

        if (isReservedFieldName(fieldName)) {
            diagnostics.reportFieldNameIsReserved(syntax.getIdentifier().getLocation(), fieldName);
            return;
        }

        if (typeScope.isFieldOrFunctionDeclared(fieldName)) {
            if (typeScope.isField(fieldName))
                diagnostics.reportFieldAlreadyDeclared(syntax.getIdentifier().getLocation(), fieldName);
            else diagnostics.reportFunctionDeclaredButFieldExpected(syntax.getIdentifier().getLocation(), fieldName);
            return;
        }

        boolean isReadOnly = syntax.getKeywordToken().getKind() == SyntaxKind.CONST_KEYWORD;
        boolean isShared = syntax.getSharedToken() != null;
        Visibility visibility = syntax.getVisibilityToken() == null ? Visibility.PRIVATE : syntax.getVisibilityToken().getKind() == SyntaxKind.PUB_KEYWORD ? Visibility.PUBLIC : Visibility.PRIVATE;
        TypeSymbol fieldType = bindTypeClause(syntax.getTypeClause());

        if (fieldType == null) {
            diagnostics.reportUnknownType(syntax.getTypeClause().getTypeName().getLocation(), syntax.getTypeClause().getTypeName().getText());
            return;
        }

        FieldSymbol field = new FieldSymbol(fieldName, isReadOnly, isShared, visibility, fieldType);
        typeScope.declareField(field);
    }


    private void bindTypeFieldDeclaration(TypeFieldDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;

        String fieldName = syntax.getIdentifier().getText();

        if (!typeScope.isFieldOrFunctionDeclared(fieldName)) {
            diagnostics.reportFieldNotDeclared(syntax.getIdentifier().getLocation(), fieldName);
            return;
        }

        if (!typeScope.isField(fieldName)) {
            diagnostics.reportFunctionDeclaredButFieldExpected(syntax.getIdentifier().getLocation(), fieldName);
            return;
        }

        FieldSymbol field = typeScope.getField(fieldName);

        BoundExpression initializer = null;
        if (syntax.getInitializer() != null) {
            initializer = bindExpression(syntax.getInitializer());
        }

        if (field.isReadonly() && initializer == null) {
            diagnostics.reportConstFieldMustHaveInitializer(syntax.getIdentifier().getLocation(), fieldName);
            return;
        }

        if (field.getType() == null) {
            diagnostics.reportUnknownType(syntax.getTypeClause().getTypeName().getLocation(), syntax.getTypeClause().getTypeName().getText());
            return;
        }

        if (field.getType() instanceof ArrayTypeSymbol array) {
            if (initializer instanceof BoundArrayLiteralExpression arrayLiteralExpression) {
                if (arrayLiteralExpression.getType().equals(new ArrayTypeSymbol(Types.UNKNOWN))) {
                    initializer = new BoundArrayLiteralExpression(arrayLiteralExpression.getSyntax(), array, arrayLiteralExpression.getElements());

                    // TODO: check for literal
                }
            }
        }

        if (isTypeGeneric(field.getType())) {
            if (initializer instanceof BoundArrayLiteralExpression arrayLiteral) {
                if (arrayLiteral.getElements().size() > 0) {
                    diagnostics.reportCannotConvert(syntax.getInitializer().getLocation(), arrayLiteral.getType(), field.getType());
                    return;
                }
            }

            if (initializer != null)
                initializer = bindConversion(initializer, initializer.getType(), field.getType());
        }

        if (initializer != null && !initializer.getType().equals(field.getType())) {
            diagnostics.reportCannotConvert(syntax.getInitializer().getLocation(), initializer.getType(), field.getType());
            return;
        }

        typeScope.defineField(field, initializer);
    }

    private void declareTypeFunctionDeclaration(TypeFunctionDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String functionName = syntax.getIdentifier().getText();

        if (typeScope.isFieldOrFunctionDeclared(functionName)) {
            if (typeScope.isFunction(functionName))
                diagnostics.reportFunctionAlreadyDeclared(syntax.getIdentifier().getLocation(), functionName);
            else diagnostics.reportFieldDeclaredButFunctionExpected(syntax.getIdentifier().getLocation(), functionName);
            return;
        }

        boolean isShared = syntax.getSharedToken() != null;
        Visibility visibility = syntax.getVisibilityToken() == null ? Visibility.PRIVATE : syntax.getVisibilityToken().getKind() == SyntaxKind.PUB_KEYWORD ? Visibility.PUBLIC : Visibility.PRIVATE;

        List<ParameterSymbol> parameters = bindParameters(syntax.getParameters());
        TypeSymbol returnType = bindTypeClause(syntax.getTypeClause());

        if (returnType == null) {
            diagnostics.reportUnknownType(syntax.getTypeClause().getTypeName().getLocation(), syntax.getTypeClause().getTypeName().getText());
            return;
        }

        FunctionSymbol function = new FunctionSymbol(functionName, isShared, visibility, parameters, returnType);
        typeScope.declareFunction(function);
    }

    private void bindTypeFunctionDeclaration(TypeFunctionDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String functionName = syntax.getIdentifier().getText();

        if (!typeScope.isFieldOrFunctionDeclared(functionName)) {
            diagnostics.reportFunctionNotDeclared(syntax.getIdentifier().getLocation(), functionName);
            return;
        }

        if (!typeScope.isFunction(functionName)) {
            diagnostics.reportFieldDeclaredButFunctionExpected(syntax.getIdentifier().getLocation(), functionName);
            return;
        }

        FunctionSymbol function = typeScope.getFunction(functionName);
        currentFunctionOrConstructor = function;

        BoundBlockStatement body = Lowerer.lower(function, bindBlockStatement(syntax.getBody()));

        currentFunctionOrConstructor = null;
        typeScope.defineFunction(function, body);
    }

    private BoundBlockStatement bindBlockStatement(BlockStatementSyntax syntax) {
        scope = new BoundScope(scope, BoundScopeKind.BLOCK);
        List<BoundStatement> statements = new ArrayList<>();

        for (StatementSyntax statement : syntax.getStatements()) {
            BoundStatement boundStatement = bindStatement(statement);
            statements.add(boundStatement);
        }

        scope = scope.getParent();
        return new BoundBlockStatement(syntax, statements);
    }

    private BoundStatement bindStatement(StatementSyntax syntax) {
        return switch (syntax.getKind()) {
            case VARIABLE_DECLARATION -> bindVariableDeclaration((VariableDeclarationSyntax) syntax);
            case EXPRESSION_STATEMENT -> bindExpressionStatement((ExpressionStatementSyntax) syntax);
            case RETURN_STATEMENT -> bindReturnStatement((ReturnStatementSyntax) syntax);
            case IF_STATEMENT -> bindIfStatement((IfStatementSyntax) syntax);
            case WHILE_STATEMENT -> bindWhileStatement((WhileStatementSyntax) syntax);
            case FOR_STATEMENT -> bindForStatement((ForStatementSyntax) syntax);
            case BREAK_STATEMENT -> bindBreakStatement((BreakStatementSyntax) syntax);
            case CONTINUE_STATEMENT -> bindContinueStatement((ContinueStatementSyntax) syntax);
            case BLOCK_STATEMENT -> bindBlockStatement((BlockStatementSyntax) syntax);
            case DO_WHILE_STATEMENT -> bindDoWhileStatement((DoWhileStatementSyntax) syntax);
            default ->
                    throw new RuntimeException(syntax.getLocation() + " Unexpected statement kind: " + syntax.getKind());
        };
    }

    private BoundStatement bindDoWhileStatement(DoWhileStatementSyntax syntax) {
        diagnostics.reportTodoFeature(syntax.getLocation(), "do-while statement");
        return bindErrorStatement(syntax);
    }

    private BoundStatement bindContinueStatement(ContinueStatementSyntax syntax) {
        if (loopStack.isEmpty()) {
            diagnostics.reportInvalidBreakOrContinue(syntax.getLocation(), syntax.getContinueKeyword().getText());
            return bindErrorStatement(syntax);
        }

        BoundLabel continueLabel = loopStack.peek().getItem1();
        return new BoundGotoStatement(syntax, continueLabel);
    }

    private BoundStatement bindBreakStatement(BreakStatementSyntax syntax) {
        if (loopStack.isEmpty()) {
            diagnostics.reportInvalidBreakOrContinue(syntax.getLocation(), syntax.getBreakKeyword().getText());
            return bindErrorStatement(syntax);
        }

        BoundLabel breakLabel = loopStack.peek().getItem2();
        return new BoundGotoStatement(syntax, breakLabel);
    }

    private BoundStatement bindForStatement(ForStatementSyntax syntax) {
        diagnostics.reportTodoFeature(syntax.getLocation(), "for statement");
        return bindErrorStatement(syntax);
    }

    private BoundStatement bindWhileStatement(WhileStatementSyntax syntax) {
        BoundExpression condition = bindExpression(syntax.getCondition());

        if (!condition.getType().equals(Types.BOOL)) {
            diagnostics.reportCannotConvert(syntax.getCondition().getLocation(), condition.getType(), Types.BOOL);
            return bindErrorStatement(syntax);
        }

        Triple<BoundStatement, BoundLabel, BoundLabel> boundBody = bindLoopBody(syntax.getBody());

        return new BoundWhileStatement(syntax, condition, boundBody.getItem1(), boundBody.getItem2(), boundBody.getItem3());
    }

    private Triple<BoundStatement, BoundLabel, BoundLabel> bindLoopBody(StatementSyntax body) {
        labelCounter++;

        BoundLabel breakLabel = new BoundLabel("break$" + labelCounter);
        BoundLabel continueLabel = new BoundLabel("continue$" + labelCounter);

        loopStack.push(new Tuple<>(continueLabel, breakLabel));
        BoundStatement boundBody = bindStatement(body);
        loopStack.pop();

        return new Triple<>(boundBody, continueLabel, breakLabel);
    }

    private BoundStatement bindIfStatement(IfStatementSyntax syntax) {
        BoundExpression condition = bindExpression(syntax.getCondition());

        if (!condition.getType().equals(Types.BOOL)) {
            diagnostics.reportCannotConvert(syntax.getCondition().getLocation(), condition.getType(), Types.BOOL);
            return bindErrorStatement(syntax);
        }

        BoundStatement thenStatement = bindStatement(syntax.getThenStatement());
        BoundStatement elseStatement = syntax.getElseClause() == null ? null : bindStatement(syntax.getElseClause().getElseStatement());

        return new BoundIfStatement(syntax, condition, thenStatement, elseStatement);
    }

    private BoundStatement bindReturnStatement(ReturnStatementSyntax syntax) {
        BoundExpression expression = syntax.getExpression() == null ? null : bindExpression(syntax.getExpression());

        if (currentFunctionOrConstructor instanceof ConstructorSymbol && expression != null) {
            diagnostics.cannotReturnExpressionOnConstructor(syntax.getLocation());
            return bindErrorStatement(syntax);
        }

        if (currentFunctionOrConstructor instanceof ConstructorSymbol) {
            return new BoundReturnStatement(syntax, null);
        }

        if (currentBinaryOperator != null) {
            if (expression == null) {
                diagnostics.cannotReturnVoidOnNonVoidFunction(syntax.getLocation());
                return bindErrorStatement(syntax);
            }

            return new BoundReturnStatement(syntax, expression);
        }

        if (currentUnaryOperator != null) {
            if (expression == null) {
                diagnostics.cannotReturnVoidOnNonVoidFunction(syntax.getLocation());
                return bindErrorStatement(syntax);
            }

            return new BoundReturnStatement(syntax, expression);
        }

        if (!(currentFunctionOrConstructor instanceof FunctionSymbol currentFunction)) {
            diagnostics.reportInvalidReturn(syntax.getLocation());
            return bindErrorStatement(syntax);
        }

        if (currentFunction.getType().equals(Types.VOID) && expression != null) {
            diagnostics.cannotReturnExpressionOnVoidFunction(syntax.getLocation());
            return bindErrorStatement(syntax);
        }

        if (!currentFunction.getType().equals(Types.VOID) && expression == null) {
            diagnostics.cannotReturnVoidOnNonVoidFunction(syntax.getLocation());
            return bindErrorStatement(syntax);
        }

        if (expression != null && !expression.getType().equals(currentFunction.getType())) {
            diagnostics.reportInvalidReturnExpression(syntax.getExpression().getLocation(), expression.getType(), currentFunction.getType());
            return bindErrorStatement(syntax);
        }

        return new BoundReturnStatement(syntax, expression);
    }

    private BoundStatement bindExpressionStatement(ExpressionStatementSyntax syntax) {
        return new BoundExpressionStatement(syntax, bindExpression(syntax.getExpression()));
    }

    private BoundStatement bindVariableDeclaration(VariableDeclarationSyntax syntax) {
        String variableName = syntax.getIdentifier().getText();

        if (scope.isVariableDeclared(variableName)) {
            diagnostics.reportVariableAlreadyDeclared(syntax.getIdentifier().getLocation(), variableName);
            return bindErrorStatement(syntax);
        }

        boolean isReadOnly = syntax.getKeywordToken().getKind() == SyntaxKind.CONST_KEYWORD;
        TypeSymbol variableType = bindTypeClause(syntax.getTypeClause());

        BoundExpression initializer = null;
        if (syntax.getInitializer() != null) {
            initializer = bindExpression(syntax.getInitializer());
        }

        if (isReadOnly && initializer == null) {
            diagnostics.reportConstVariableMustBeInitialized(syntax.getLocation(), variableName);
            return bindErrorStatement(syntax);
        }

        if (variableType == null) {
            diagnostics.reportUnknownType(syntax.getTypeClause().getTypeName().getLocation(), syntax.getTypeClause().getTypeName().getText());
            return bindErrorStatement(syntax);
        }

        VariableSymbol variableSymbol = new VariableSymbol(variableName, isReadOnly, variableType);

        if (initializer instanceof BoundInstanceCreationExpression instanceCreationExpression) {
            variableSymbol.setGenericTypes(instanceCreationExpression.getGenericTypes());
        }

        if (initializer != null) {
            if (!initializer.getType().equals(variableType)) {
                diagnostics.reportCannotConvert(syntax.getInitializer().getLocation(), initializer.getType(), variableType);
                return bindErrorStatement(syntax);
            }
        }

        scope.defineVariable(variableSymbol, initializer);

        return new BoundVariableDeclaration(syntax, variableSymbol, initializer);
    }

    private List<ParameterSymbol> bindParameters(SeparatedSyntaxList<ParameterSyntax> parameters) {
        List<ParameterSymbol> parameterSymbols = new ArrayList<>();
        List<String> seenParameterNames = new ArrayList<>();

        for (ParameterSyntax parameter : parameters) {
            String parameterName = parameter.getIdentifier().getText();
            if (seenParameterNames.contains(parameterName)) {
                diagnostics.reportParameterAlreadyDeclared(parameter.getIdentifier().getLocation(), parameterName);
                continue;
            }

            TypeSymbol parameterType = bindTypeClause(parameter.getTypeClause());
            if (parameterType == null) {
                diagnostics.reportUnknownType(parameter.getTypeClause().getTypeName().getLocation(), parameter.getTypeClause().getTypeName().getText());
                continue;
            }

            ParameterSymbol parameterSymbol = new ParameterSymbol(parameterName, parameterType);
            parameterSymbols.add(parameterSymbol);
            seenParameterNames.add(parameterName);
        }

        return parameterSymbols;
    }


    private void declareTypeConstructorDeclaration(TypeConstructorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;

        List<ParameterSymbol> parameters = bindParameters(syntax.getParameters());
        if (typeScope.isConstructorDefined(parameters.size())) {
            diagnostics.reportConstructorParameterWithCountAlreadyDefined(TextLocation.fromLocations(syntax.getConstructorKeyword().getLocation(), syntax.getCloseParenToken().getLocation()), parameters.size());
            return;
        }

        ConstructorSymbol constructor = new ConstructorSymbol(parameters);
        typeScope.declareConstructor(constructor);
    }

    private void bindTypeConstructorDeclaration(TypeConstructorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;

        List<ParameterSymbol> parameters = bindParameters(syntax.getParameters());
        if (!typeScope.isConstructorDefined(parameters.size())) {
            diagnostics.reportConstructorParameterWithCountNotDefined(TextLocation.fromLocations(syntax.getConstructorKeyword().getLocation(), syntax.getCloseParenToken().getLocation()), currentType, parameters.size());
            return;
        }

        ConstructorSymbol constructor = typeScope.getType().getConstructor(parameters.size());
        currentFunctionOrConstructor = constructor;

        BoundBlockStatement body = Lowerer.lower(constructor, bindBlockStatement(syntax.getBody()));

        currentFunctionOrConstructor = null;
        typeScope.defineConstructor(constructor, body);
    }


    private void declareTypeBinaryOperatorDeclaration(TypeBinaryOperatorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String operatorName = syntax.getOperatorToken().getText();

        TypeSymbol right = bindTypeClause(syntax.getRightOperandType());
        TypeSymbol result = bindTypeClause(syntax.getReturnType());

        if (right == null) {
            diagnostics.reportUnknownType(syntax.getRightOperandType().getTypeName().getLocation(), syntax.getRightOperandType().getTypeName().getText());
            return;
        }

        if (result == null) {
            diagnostics.reportUnknownType(syntax.getReturnType().getTypeName().getLocation(), syntax.getReturnType().getTypeName().getText());
            return;
        }

        String rightOperandName = syntax.getRightOperandToken().getText();

        if (typeScope.isBinaryOperatorDeclared(operatorName, right)) {
            diagnostics.reportBinaryOperatorAlreadyDeclared(syntax.getOperatorToken().getLocation(), operatorName, typeScope.getType(), right);
            return;
        }

        BinaryOperatorSymbol operator = new BinaryOperatorSymbol(operatorName, rightOperandName, right, typeScope.getType());
        typeScope.declareBinaryOperator(operator);
    }

    private void bindTypeBinaryOperatorDeclaration(TypeBinaryOperatorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String operatorName = syntax.getOperatorToken().getText();
        TypeSymbol returnType = bindTypeClause(syntax.getReturnType());

        if (returnType == Types.VOID) {
            diagnostics.reportUnaryOperatorCannotBeVoid(syntax.getReturnType().getTypeName().getLocation(), operatorName);
            return;
        }

        if (!typeScope.isBinaryOperatorDeclared(operatorName, typeScope.getType())) {
            diagnostics.reportBinaryOperatorNotDeclared(syntax.getOperatorToken().getLocation(), operatorName, typeScope.getType());
            return;
        }

        BinaryOperatorSymbol operator = typeScope.getBinaryOperator(operatorName, typeScope.getType());
        currentBinaryOperator = operator;

        BoundBlockStatement body = bindBlockStatement(syntax.getBody());

        currentBinaryOperator = null;
        typeScope.defineBinaryOperator(operator, body);
    }

    private void declareTypeUnaryOperatorDeclaration(TypeUnaryOperatorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String operatorName = syntax.getOperatorToken().getText();
        TypeSymbol returnType = bindTypeClause(syntax.getReturnType());

        if (returnType == Types.VOID) {
            diagnostics.reportUnaryOperatorCannotBeVoid(syntax.getReturnType().getTypeName().getLocation(), operatorName);
            return;
        }

        if (typeScope.isUnaryOperatorDeclared(operatorName)) {
            diagnostics.reportUnaryOperatorAlreadyDeclared(syntax.getOperatorToken().getLocation(), operatorName);
            return;
        }

        UnaryOperatorSymbol operator = new UnaryOperatorSymbol(operatorName, returnType);
        typeScope.declareUnaryOperator(operator);
    }

    private void bindTypeUnaryOperatorDeclaration(TypeUnaryOperatorDeclarationSyntax syntax) {
        BoundTypeScope typeScope = (BoundTypeScope) scope;
        String operatorName = syntax.getOperatorToken().getText();

        if (!typeScope.isUnaryOperatorDeclared(operatorName)) {
            diagnostics.reportUnaryOperatorNotDeclared(syntax.getOperatorToken().getLocation(), operatorName, typeScope.getType());
            return;
        }

        UnaryOperatorSymbol operator = typeScope.getUnaryOperator(operatorName);
        currentUnaryOperator = operator;

        BoundBlockStatement body = bindBlockStatement(syntax.getBody());

        currentUnaryOperator = null;
        typeScope.defineUnaryOperator(operator, body);
    }

    private void bindImportDeclaration(ImportDeclarationSyntax syntax) {
        try {
            String pathToImport = (String) syntax.getStringToken().getValue();
            boolean isStd = pathToImport.startsWith("std:");
            String path = isStd ? standardLibrary.getLibraryPath(pathToImport) : pathToImport;

            // convert relative path to absolute path
            if (!isStd) {
                path = Paths.get(syntaxTree.getSourceText().getFilePath()).getParent().resolve(path).toString();
            }

            if (!isStd) {
                // TODO: Support importing non-Zephyr files
                if (path.endsWith(".zph")) {
                    diagnostics.reportImportError(syntax.getStringToken().getLocation(), "Importing non-Zephyr files is not supported yet");
                    path = path.substring(0, path.length() - 4);
                } else {
                    path = path + ".zph";
                }
            }

            if (!Files.exists(Paths.get(path))) {
                if (Files.exists(Paths.get(path.substring(0, path.length() - 4)))) {
                    diagnostics.reportImportError(syntax.getStringToken().getLocation(), "Importing non-Zephyr files is not supported yet");
                } else {
                    diagnostics.reportImportError(syntax.getStringToken().getLocation(), "File %s does not exist".formatted(path));
                }
                return;
            }

            SourceText importedSourceText = SourceText.fromFile(path);
            SyntaxTree importedSyntaxTree = SyntaxTree.parse(importedSourceText);
            Binder importedBinder = new Binder(importedSyntaxTree, standardLibrary);
            BoundProgram importedProgram = importedBinder.bindProgram();

            if (importedProgram.getDiagnostics().hasErrors()) {
                diagnostics.reportImportError(syntax.getStringToken().getLocation(), "Imported program has errors");
                diagnostics.addAll(importedBinder.diagnostics);
                return;
            }

            programScope.importProgram((String) syntax.getStringToken().getValue(), importedProgram.getProgramScope());
        } catch (Exception e) {
            diagnostics.reportImportError(syntax.getStringToken().getLocation(), e.getMessage());
        }
    }

    private BoundExpression bindExpression(ExpressionSyntax syntax) {
        return switch (syntax.getKind()) {
            case PARENTHESIZED_EXPRESSION -> bindParenthesizedExpression((ParenthesizedExpressionSyntax) syntax);
            case LITERAL_EXPRESSION -> bindLiteralExpression((LiteralExpressionSyntax) syntax);
            case BINARY_EXPRESSION -> bindBinaryExpression((BinaryExpressionSyntax) syntax);
            case UNARY_EXPRESSION -> bindUnaryExpression((UnaryExpressionSyntax) syntax);
            case CONDITIONAL_EXPRESSION -> bindConditionalExpression((ConditionalExpressionSyntax) syntax);
            case INSTANCE_CREATION_EXPRESSION ->
                    bindInstanceCreationExpression((InstanceCreationExpressionSyntax) syntax);
            case ARRAY_LITERAL_EXPRESSION -> bindArrayLiteralExpression((ArrayLiteralExpressionSyntax) syntax);
            case ARRAY_CREATION_EXPRESSION -> bindArrayCreationExpression((ArrayCreationExpressionSyntax) syntax);
            case MEMBER_ACCESS_EXPRESSION -> bindMemberAccessExpression((MemberAccessExpressionSyntax) syntax);
            case NAME_EXPRESSION -> bindNameExpression((NameExpressionSyntax) syntax);
            case ASSIGNMENT_EXPRESSION -> bindAssignmentExpression((AssignmentExpressionSyntax) syntax);
            case ARRAY_ACCESS_EXPRESSION -> bindArrayAccessExpression((ArrayAccessExpressionSyntax) syntax);
            case FUNCTION_CALL_EXPRESSION -> bindFunctionCallExpression((FunctionCallExpressionSyntax) syntax);
            case TYPE_CHECK_EXPRESSION -> bindTypeCheckExpression((TypeCheckExpressionSyntax) syntax);
            default -> bindErrorExpression(syntax);
        };
    }

    private BoundExpression bindTypeCheckExpression(TypeCheckExpressionSyntax syntax) {
        String typeName = syntax.getQualifiedName().getText();
        BoundExpression left = bindExpression(syntax.getLeft());

        // TODO: check generic types

        TypeSymbol rightType = getTypeSymbol(typeName);
        if (rightType == null) {
            diagnostics.reportUndefinedType(syntax.getQualifiedName().getLocation(), typeName);
            return bindErrorExpression(syntax);
        }

        if (left.getType().isGeneric()) {
            left = bindConversion(left, left.getType(), rightType);

            // check right type is a generic type
        }


        if (left.getType() == Types.VOID) {
            diagnostics.reportCannotCheckTypeOfVoid(syntax.getLeft().getLocation());
            return bindErrorExpression(syntax);
        }

        if (left.getType().equals(Types.ANY)) {
            return new BoundTypeCheckExpression(syntax, left, rightType);
        }

        if (rightType.equals(Types.ANY)) {
            return left;
        }

        if (!left.getType().equals(rightType)) {
            diagnostics.reportCannotCheckType(syntax.getLocation(), left.getType(), rightType);
            return bindErrorExpression(syntax);
        }

        diagnostics.reportCannotCheckType(syntax.getLocation(), left.getType(), rightType);
        return bindExpression(syntax.getLeft());
    }

    private BoundExpression bindArrayCreationExpression(ArrayCreationExpressionSyntax syntax) {
        String typeName = syntax.getQualifiedName().getText();

        TypeSymbol type = getTypeSymbol(typeName);
        if (type == null) {
            diagnostics.reportUndefinedType(syntax.getQualifiedName().getLocation(), typeName);
            return bindErrorExpression(syntax);
        }

        HashMap<BoundExpression, BoundExpression> sizes = new HashMap<>();
        for (ArraySizeClauseSyntax size : syntax.getArraySizeClauses()) {
            BoundExpression boundSize = bindExpression(size.getSize());

            if (boundSize.getType() != Types.INT) {
                diagnostics.reportArrayCreationSizeMustBeInt(size.getSize().getLocation());
                return bindErrorExpression(syntax);
            }

            BoundExpression initializer = null;
            if (size.getInitialArrayValueClause() != null) {
                initializer = bindExpression(size.getInitialArrayValueClause().getInitializer());

                if (!initializer.getType().equals(type)) {
                    diagnostics.reportArrayCreationInitializerTypeMismatch(size.getInitialArrayValueClause().getInitializer().getLocation(), type, initializer.getType());
                    return bindErrorExpression(syntax);
                }
            }

            sizes.put(boundSize, initializer);
        }

        if (sizes.isEmpty()) {
            diagnostics.reportArrayCreationMustHaveSize(syntax.getLocation());
            return bindErrorExpression(syntax);
        }

        int rank = sizes.size();
        for (int i = 0; i < rank; i++) {
            type = new ArrayTypeSymbol(type);
        }

        return new BoundArrayCreationExpression(syntax, type, sizes);
    }

    private BoundExpression bindArrayLiteralExpression(ArrayLiteralExpressionSyntax syntax) {
        List<BoundExpression> elements = new ArrayList<>();

        for (ExpressionSyntax element : syntax.getElements()) {
            BoundExpression boundElement = bindExpression(element);
            elements.add(boundElement);
        }

        if (elements.isEmpty()) {
            return new BoundArrayLiteralExpression(syntax, new ArrayTypeSymbol(Types.UNKNOWN), elements);
        }

        ArrayTypeSymbol type = new ArrayTypeSymbol(elements.get(0).getType());

        for (BoundExpression element : elements) {
            if (!element.getType().equals(type.getElementType())) {
                diagnostics.reportCannotConvert(element.getSyntax().getLocation(), element.getType(), type.getElementType());
                return bindErrorExpression(syntax);
            }
        }

        return new BoundArrayLiteralExpression(syntax, type, elements);
    }

    private BoundExpression bindAssignmentExpression(AssignmentExpressionSyntax syntax) {
        BoundExpression target = bindExpression(syntax.getLeft());
        BoundExpression expression = bindExpression(syntax.getRight());

        if (target instanceof BoundVariableExpression variable) {
            VariableSymbol variableSymbol = variable.getVariable();
            return getBoundExpression(syntax, variable, expression, variableSymbol);
        } else if (target instanceof BoundFieldAccessExpression field) {
            FieldSymbol fieldSymbol = field.getField();
            return getBoundExpression(syntax, field, expression, fieldSymbol);
        } else if (target instanceof BoundMemberAccessExpression memberAccessExpression) {
            if (memberAccessExpression.getMember() instanceof VariableSymbol variable) {
                return getBoundExpression(syntax, memberAccessExpression, expression, variable);
            } else if (memberAccessExpression.getMember() instanceof FieldSymbol field) {
                return getBoundExpression(syntax, memberAccessExpression, expression, field);
            } else {
                diagnostics.reportInvalidAssignmentTarget(syntax.getLeft().getLocation(), target.getSyntax());
                return bindErrorExpression(syntax);
            }
        } else if (target instanceof BoundArrayAccessExpression arrayAccessExpression) {
            BoundExpression array = arrayAccessExpression.getTarget();
            BoundExpression index = arrayAccessExpression.getIndex();

            if (!(array.getType() instanceof ArrayTypeSymbol arrayType)) {
                diagnostics.reportInvalidAssignmentTarget(syntax.getLeft().getLocation(), target.getSyntax());
                return bindErrorExpression(syntax);
            }

            if (!index.getType().equals(Types.INT)) {
                diagnostics.reportCannotConvert(index.getSyntax().getLocation(), index.getType(), Types.INT);
                return bindErrorExpression(syntax);
            }

            if (!expression.getType().equals(arrayType.getElementType())) {
                diagnostics.reportCannotConvert(expression.getSyntax().getLocation(), expression.getType(), arrayType.getElementType());
                return bindErrorExpression(syntax);
            }

            return new BoundAssignmentExpression(syntax, arrayAccessExpression, expression);
        }

        diagnostics.reportInvalidAssignmentTarget(syntax.getLeft().getLocation(), target.getSyntax());
        return bindErrorExpression(syntax);
    }

    private BoundExpression getBoundExpression(AssignmentExpressionSyntax syntax, BoundExpression target, BoundExpression value, FieldSymbol field) {
        if (field.isReadonly()) {
            diagnostics.reportCannotAssign(syntax.getOperatorToken().getLocation(), field.getName());
            return bindErrorExpression(syntax);
        }

        if (field.getType() instanceof ArrayTypeSymbol array) {
            if (value instanceof BoundArrayLiteralExpression arrayLiteralExpression) {
                if (arrayLiteralExpression.getType().equals(new ArrayTypeSymbol(Types.UNKNOWN))) {
                    value = new BoundArrayLiteralExpression(arrayLiteralExpression.getSyntax(), array, arrayLiteralExpression.getElements());

                    // TODO: check for generic
                }
            }
        }

        if (isTypeGeneric(value.getType())) {
            if (value instanceof BoundArrayLiteralExpression arrayLiteral) {
                if (arrayLiteral.getElements().size() > 0) {
                    diagnostics.reportCannotConvertArrayLiteral(syntax.getRight().getLocation(), arrayLiteral.getType(), field.getType());
                    return bindErrorExpression(syntax);
                }
            }

            value = new BoundConversionExpression(value.getSyntax(), field.getType(), value.getType(), value);
        }

        if (!field.getType().equals(value.getType())) {
            diagnostics.reportCannotConvert(syntax.getRight().getLocation(), value.getType(), field.getType());
            return bindErrorExpression(syntax);
        }

        if (syntax.getOperatorToken().getKind() != SyntaxKind.EQUALS_TOKEN) {
            diagnostics.reportTodoFeature(syntax.getOperatorToken().getLocation(), "Compound assignment");
            return bindErrorExpression(syntax);
        }

        return new BoundAssignmentExpression(syntax, target, value);
    }

    private BoundExpression getBoundExpression(AssignmentExpressionSyntax syntax, BoundExpression target, BoundExpression value, VariableSymbol variable) {
        if (variable.isReadonly()) {
            diagnostics.reportCannotAssign(syntax.getOperatorToken().getLocation(), variable.getName());
            return bindErrorExpression(syntax);
        }

        if (variable.getType() instanceof ArrayTypeSymbol array) {
            if (value instanceof BoundArrayLiteralExpression arrayLiteralExpression) {
                if (arrayLiteralExpression.getType().equals(new ArrayTypeSymbol(Types.UNKNOWN))) {
                    value = new BoundArrayLiteralExpression(arrayLiteralExpression.getSyntax(), array, arrayLiteralExpression.getElements());
                }
            }
        }

        if (isTypeGeneric(value.getType())) {
            if (value instanceof BoundArrayLiteralExpression arrayLiteral) {
                if (arrayLiteral.getElements().size() > 0) {
                    diagnostics.reportCannotConvertArrayLiteral(value.getSyntax().getLocation(), arrayLiteral.getType(), variable.getType());
                    return bindErrorExpression(syntax);
                }
            }

            value = new BoundConversionExpression(value.getSyntax(), variable.getType(), value.getType(), value);
        }

        if (!variable.getType().equals(value.getType())) {
            diagnostics.reportCannotConvert(syntax.getRight().getLocation(), value.getType(), variable.getType());
            return bindErrorExpression(syntax);
        }

        if (syntax.getOperatorToken().getKind() != SyntaxKind.EQUALS_TOKEN) {
            diagnostics.reportTodoFeature(syntax.getOperatorToken().getLocation(), "Compound assignment");
            return bindErrorExpression(syntax);
        }

        return new BoundAssignmentExpression(syntax, target, value);
    }

    private boolean isTypeGeneric(TypeSymbol type) {
        if (type.isGeneric()) {
            return true;
        }

        if (type instanceof ArrayTypeSymbol array) {
            return isTypeGeneric(array.getElementType());
        }

        return false;
    }

    private BoundExpression bindArrayAccessExpression(ArrayAccessExpressionSyntax syntax) {
        BoundExpression target = bindExpression(syntax.getTarget());

        if (target instanceof BoundErrorExpression) {
            return target;
        }

        if (!(target.getType() instanceof ArrayTypeSymbol)) {
            diagnostics.reportCannotIndex(syntax.getTarget().getLocation(), target.getType());
            return bindErrorExpression(syntax);
        }

        BoundExpression index = bindExpression(syntax.getIndex());

        if (!index.getType().equals(Types.INT)) {
            diagnostics.reportCannotConvert(syntax.getIndex().getLocation(), index.getType(), Types.INT);
            return bindErrorExpression(syntax);
        }

        return new BoundArrayAccessExpression(syntax, target, index);
    }

    private BoundExpression bindMemberAccessExpression(MemberAccessExpressionSyntax syntax) {
        BoundExpression target = bindExpression(syntax.getTarget());
        String member = syntax.getMember().getText();

        if (target instanceof BoundErrorExpression) {
            return target;
        }

        if (target instanceof BoundTypeExpression type) {
            TypeSymbol typeSymbol = type.getType();
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;
                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        if (target instanceof BoundVariableExpression variable) {
            TypeSymbol typeSymbol = variable.getVariable().getType();
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;

                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        if (target instanceof BoundFieldAccessExpression field) {
            TypeSymbol typeSymbol = field.getField().getType();
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;

                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        if (target instanceof BoundThisExpression) {
            TypeSymbol typeSymbol = currentType;
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;

                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        if (target instanceof BoundFunctionCallExpression functionCall) {
            TypeSymbol typeSymbol = functionCall.getFunction().getType();
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;

                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        if (target instanceof BoundConversionExpression conversion) {
            TypeSymbol typeSymbol = conversion.getType();
            if (typeSymbol.isFieldOrFunctionDeclared(member)) {
                BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
                if (result != null) return result;

                return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
            }

            diagnostics.reportUndefinedMember(syntax.getMember().getLocation(), typeSymbol.getName(), member);
            return bindErrorExpression(syntax);
        }

        TypeSymbol typeSymbol = target.getType();
        if (typeSymbol.isFieldOrFunctionDeclared(member)) {
            BoundExpression result = bindAndCheckMemberAccess(syntax, target, member, typeSymbol);
            if (result != null) return result;

            return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
        }

        diagnostics.reportInvalidMemberAccess(target.getSyntax().getLocation(), target.getSyntax());
        return bindErrorExpression(syntax);
    }

    private BoundExpression bindAndCheckMemberAccess(MemberAccessExpressionSyntax syntax, BoundExpression target, String member, TypeSymbol typeSymbol) {
        if (typeSymbol.getFieldOrFunction(member) instanceof FieldSymbol field) {
            if (field.getVisibility() == Visibility.PRIVATE && !currentType.equals(typeSymbol)) {
                diagnostics.reportCannotAccessPrivateMember(syntax.getMember().getLocation(), typeSymbol.getName(), member, "field");
                return bindErrorExpression(syntax);
            }
            return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
        }

        if (typeSymbol.getFieldOrFunction(member) instanceof FunctionSymbol function) {
            if (function.getVisibility() == Visibility.PRIVATE && !currentType.equals(typeSymbol)) {
                diagnostics.reportCannotAccessPrivateMember(syntax.getMember().getLocation(), typeSymbol.getName(), member, "function");
                return bindErrorExpression(syntax);
            }
            return new BoundMemberAccessExpression(syntax, target, typeSymbol.getFieldOrFunction(member));
        }
        return null;
    }

    private BoundExpression bindNameExpression(NameExpressionSyntax syntax) {
        String name = syntax.getIdentifier().getText();
        VariableSymbol variable = scope.getVariable(name);

        if (name.equals("this") && (currentFunctionOrConstructor != null || currentBinaryOperator != null || currentUnaryOperator != null)) {
            return new BoundThisExpression(syntax, currentType);
        }

        if (variable == null) {
            if (currentFunctionOrConstructor != null) {
                if (currentFunctionOrConstructor.getParameters().stream().anyMatch(p -> p.getName().equals(name))) {
                    ParameterSymbol parameterSymbol = currentFunctionOrConstructor.getParameter(name);
                    VariableSymbol parameterVariable = new VariableSymbol(name, true, parameterSymbol.getType());

                    return new BoundVariableExpression(syntax, parameterVariable);
                }

                if (programScope.isTypeDeclared(name)) {
                    TypeSymbol type = programScope.getType(name);
                    return new BoundTypeExpression(syntax, type);
                }

                if (programScope.isTypeImported(name)) {
                    TypeSymbol type = programScope.getImportedType(name);
                    return new BoundTypeExpression(syntax, type);
                }
            }

            if (currentBinaryOperator != null) {
                if (name.equals(currentBinaryOperator.getOtherOperandName())) {
                    return new BoundVariableExpression(syntax, new VariableSymbol(name, true, currentBinaryOperator.getOtherType()));
                }
            }

            diagnostics.reportUndefinedName(syntax.getIdentifier().getLocation(), name);
            return bindErrorExpression(syntax);
        }


        return new BoundVariableExpression(syntax, variable);
    }

    private BoundExpression bindInstanceCreationExpression(InstanceCreationExpressionSyntax syntax) {
        String typeName = syntax.getQualifiedName().getText();
        TypeSymbol type = programScope.getType(typeName);

        if (type == null) {
            diagnostics.reportUndefinedType(syntax.getQualifiedName().getLocation(), typeName);
            return bindErrorExpression(syntax);
        }

        HashMap<String, TypeSymbol> genericTypes = new HashMap<>();

        // get generic types
        boolean isGeneric = syntax.getGenericParameterClause() != null;
        if (isGeneric) {
            int genericCount = syntax.getGenericParameterClause().getGenericParameters().count();

            if (type.getGenericCount() != genericCount) {
                diagnostics.reportGenericParameterCountMismatch(TextLocation.fromLocations(syntax.getGenericParameterClause().getLessToken().getLocation(), syntax.getGenericParameterClause().getGreaterToken().getLocation()), type, genericCount);
                return bindErrorExpression(syntax);
            }

            for (int i = 0; i < genericCount; i++) {
                String genericName = syntax.getGenericParameterClause().getGenericParameters().get(i).getIdentifier().getText();
                TypeSymbol genericType = programScope.getType(genericName);

                genericTypes.put(type.getGenericAt(i), genericType);
            }
        }

        List<BoundExpression> arguments = new ArrayList<>();
        for (ExpressionSyntax argument : syntax.getArguments()) {
            arguments.add(bindExpression(argument));
        }

        if (!type.isConstructorDefined(arguments.size())) {
            diagnostics.reportConstructorParameterWithCountNotDefined(syntax.getNewKeyword().getLocation(), type, arguments.size());
            return bindErrorExpression(syntax);
        }

        ConstructorSymbol constructor = type.getConstructor(arguments.size());
        // CHECK ARGUMENTS
        List<ParameterSymbol> parameters = constructor.getParameters();
        SeparatedSyntaxList<ExpressionSyntax> argumentsSyntax = syntax.getArguments();


        if (parameters.size() != arguments.size()) {
            diagnostics.reportConstructorParameterCountMismatch(TextLocation.fromLocations(syntax.getNewKeyword().getLocation(), syntax.getRightParenthesis().getLocation()), constructor, arguments.size());
            return bindErrorExpression(syntax);
        }

        for (int i = 0; i < parameters.size(); i++) {
            ParameterSymbol parameter = parameters.get(i);
            BoundExpression argument = arguments.get(i);
            ExpressionSyntax argumentSyntax = argumentsSyntax.get(i);

            boolean isParameterGeneric = genericTypes.containsKey(parameter.getType().getName());

            if (isParameterGeneric) {
                // check if mapped generic type equals the argument type
                TypeSymbol mappedGenericType = genericTypes.get(parameter.getType().getName());
                if (!mappedGenericType.equals(argument.getType())) {
                    diagnostics.reportMismatchingTypes(argumentSyntax.getLocation(), mappedGenericType, argument.getType());
                    return bindErrorExpression(syntax);
                }

                argument = bindConversion(argument, parameter.getType(), genericTypes.get(parameter.getType().getName()));
            }

            if (parameter.getType().equals(Types.ANY)) {
                continue;
            }

            if (!parameter.getType().equals(argument.getType())) {
                diagnostics.reportMismatchingTypes(argumentSyntax.getLocation(), parameter.getType(), argument.getType());
                return bindErrorExpression(syntax);
            }
        }

        return new BoundInstanceCreationExpression(syntax, type, arguments, genericTypes);
    }

    private BoundExpression bindFunctionCallExpression(FunctionCallExpressionSyntax syntax) {
        List<BoundExpression> boundArguments = new ArrayList<>();
        for (ExpressionSyntax argument : syntax.getArguments()) {
            boundArguments.add(bindExpression(argument));
        }

        BoundExpression callee = bindExpression(syntax.getCallee());
        if (callee instanceof BoundErrorExpression) {
            return callee;
        }

        if (callee instanceof BoundMemberAccessExpression member) {
            BoundExpression target = member.getTarget();

            if (target instanceof BoundErrorExpression) {
                return target;
            }

            if (target instanceof BoundVariableExpression variable) {
                // instance access
                FunctionSymbol function = variable.getVariable().getType().getFunction(member.getMember().getName(), false);

                if (function == null) {
                    diagnostics.reportFunctionNotDeclared(syntax.getLocation(), member.getMember().getName());
                    return bindErrorExpression(syntax);
                }

                // CHECK ARGUMENTS
                List<ParameterSymbol> parameters = function.getParameters();
                SeparatedSyntaxList<ExpressionSyntax> argumentsSyntax = syntax.getArguments();

                if (parameters.size() != boundArguments.size()) {
                    diagnostics.reportFunctionParameterCountMismatch(syntax.getCallee().getLocation(), function, boundArguments.size());
                    return bindErrorExpression(syntax);
                }

                for (int i = 0; i < parameters.size(); i++) {
                    ParameterSymbol parameter = parameters.get(i);
                    BoundExpression argument = boundArguments.get(i);
                    ExpressionSyntax argumentSyntax = argumentsSyntax.get(i);

                    boolean isParameterGeneric = variable.getVariable().isGenericType(parameter.getType().getName());

                    if (isParameterGeneric) {
                        // check if mapped generic type equals the argument type
                        TypeSymbol mappedGenericType = variable.getVariable().getGenericType(parameter.getType().getName());
                        if (!mappedGenericType.equals(argument.getType())) {
                            diagnostics.reportMismatchingTypes(argumentSyntax.getLocation(), mappedGenericType, argument.getType());
                            return bindErrorExpression(syntax);
                        }

                        argument = bindConversion(argument, parameter.getType(), variable.getVariable().getGenericType(parameter.getType().getName()));
                    }

                    if (parameter.getType().equals(Types.ANY)) {
                        continue;
                    }

                    if (!parameter.getType().equals(argument.getType())) {
                        diagnostics.reportMismatchingTypes(argumentSyntax.getLocation(), parameter.getType(), argument.getType());
                        return bindErrorExpression(syntax);
                    }
                }

                BoundExpression methodCall = new BoundFunctionCallExpression(syntax, target, function, boundArguments);
                if (variable.getVariable().isGenericType(function.getType())) {
                    methodCall = bindConversion(methodCall, variable.getVariable().getGenericType(function.getType()), function.getType());
                }
                return methodCall;
            }

            if (target instanceof BoundTypeExpression type) {
                // static access
                FunctionSymbol function = type.getType().getFunction(member.getMember().getName(), true);

                if (function == null) {
                    diagnostics.reportFunctionNotDeclared(syntax.getLocation(), member.getMember().getName());
                    return bindErrorExpression(syntax);
                }

                // CHECK ARGUMENTS
                List<ParameterSymbol> parameters = function.getParameters();
                SeparatedSyntaxList<ExpressionSyntax> argumentsSyntax = syntax.getArguments();

                if (parameters.size() != boundArguments.size()) {
                    diagnostics.reportFunctionParameterCountMismatch(syntax.getLocation(), function, boundArguments.size());
                    return bindErrorExpression(syntax);
                }

                for (int i = 0; i < parameters.size(); i++) {
                    ParameterSymbol parameter = parameters.get(i);
                    BoundExpression argument = boundArguments.get(i);
                    ExpressionSyntax argumentSyntax = argumentsSyntax.get(i);

                    if (parameter.getType().equals(Types.ANY)) {
                        continue;
                    }

                    if (!parameter.getType().equals(argument.getType())) {
                        diagnostics.reportMismatchingTypes(argumentSyntax.getLocation(), parameter.getType(), argument.getType());
                        return bindErrorExpression(syntax);
                    }
                }

                return new BoundFunctionCallExpression(syntax, target, function, boundArguments);
            }

            if (target instanceof BoundFieldAccessExpression field) {
                // instance access

                diagnostics.reportTodoFeature(syntax.getLocation(), "Method call on field");
                return bindErrorExpression(syntax);
            }

            TypeSymbol type = member.getTarget().getType();
            return new BoundFunctionCallExpression(syntax, target, type.getFunction(member.getMember().getName(), false), boundArguments);
        }

        if (callee instanceof BoundLiteralExpression literal) {
            diagnostics.reportTodoFeature(syntax.getLocation(), "Method call on literal");
            return bindErrorExpression(syntax);
        }

        if (callee instanceof BoundThisExpression thisExpression) {
            diagnostics.reportCannotCallThis(syntax.getLocation());
            return bindErrorExpression(syntax);
        }

        diagnostics.reportTodoFeature(syntax.getLocation(), "Method call on other types");
        return bindErrorExpression(syntax);
    }

    private BoundExpression bindConversion(BoundExpression expression, TypeSymbol fromType, TypeSymbol toType) {
        return new BoundConversionExpression(expression.getSyntax(), fromType, toType, expression);
    }

    private BoundExpression bindConditionalExpression(ConditionalExpressionSyntax syntax) {
        BoundExpression condition = bindExpression(syntax.getCondition());
        if (!condition.getType().equals(Types.BOOL)) {
            diagnostics.reportInvalidConditionType(syntax.getCondition().getLocation(), condition.getType());
            return bindErrorExpression(syntax);
        }

        BoundExpression thenExpression = bindExpression(syntax.getThenExpression());
        BoundExpression elseExpression = bindExpression(syntax.getElseExpression());

        return new BoundConditionalExpression(syntax, condition, thenExpression, elseExpression);
    }

    private BoundExpression bindUnaryExpression(UnaryExpressionSyntax syntax) {
        BoundExpression operand = bindExpression(syntax.getOperand());

        TypeSymbol operandType = operand.getType();
        if (operandType == Types.ERROR) {
            diagnostics.reportTodoFeature(syntax.getLocation(), "Unary operator on error expression");
            return bindErrorExpression(syntax);
        }

        if (operandType.isUnaryOperatorDefined(syntax.getOperatorToken().getText())) {
            TypeSymbol type = operandType.getUnaryOperatorType(syntax.getOperatorToken().getText());
            return new BoundUnaryExpression(syntax, syntax.getOperatorToken().getText(), operand, type);
        }

        diagnostics.reportUndefinedUnaryOperator(syntax.getOperatorToken().getLocation(), syntax.getOperatorToken().getText(), operandType);
        return bindErrorExpression(syntax);
    }

    private BoundExpression bindBinaryExpression(BinaryExpressionSyntax syntax) {
        BoundExpression left = bindExpression(syntax.getLeft());
        BoundExpression right = bindExpression(syntax.getRight());

        TypeSymbol leftType = left.getType();
        TypeSymbol rightType = right.getType();

        if (leftType == Types.ERROR || rightType == Types.ERROR) {
            return bindErrorExpression(syntax);
        }

        // if the left type is string, then we call toString on the right type.
        // toString will always be there, because it is getting generated in the Type if it is not defined.
        if (leftType.equals(Types.STRING) && !rightType.equals(Types.STRING)) {
            FunctionSymbol toString = rightType.getFunction("toString", false);
            right = new BoundFunctionCallExpression(syntax.getRight(), right, toString, List.of());
            rightType = Types.STRING;
        }

        if (leftType.isBinaryOperatorDefined(syntax.getOperatorToken().getText(), rightType)) {
            TypeSymbol type = leftType.getBinaryOperatorType(syntax.getOperatorToken().getText(), rightType);
            return new BoundBinaryExpression(syntax, left, syntax.getOperatorToken().getText(), right, type);
        }

        diagnostics.reportUndefinedBinaryOperator(syntax.getOperatorToken().getLocation(), syntax.getOperatorToken().getText(), leftType, rightType);
        return bindErrorExpression(syntax);
    }

    private BoundExpression bindLiteralExpression(LiteralExpressionSyntax syntax) {
        return switch (syntax.getValueToken().getKind()) {
            case NUMBER_TOKEN, FLOATING_POINT_TOKEN -> bindNumberLiteralExpression(syntax);
            case TRUE_KEYWORD, FALSE_KEYWORD -> bindBooleanLiteralExpression(syntax);
            case STRING_TOKEN -> bindStringLiteralExpression(syntax);
            case CHARACTER_TOKEN -> bindCharacterLiteralExpression(syntax);

            default -> {
                diagnostics.reportTodoFeature(syntax.getLocation(), "Literal expression");
                yield bindErrorExpression(syntax);
            }
        };
    }

    private BoundExpression bindCharacterLiteralExpression(LiteralExpressionSyntax syntax) {
        TypeSymbol type = Types.CHAR;
        Object value = syntax.getValueToken().getValue();
        return new BoundLiteralExpression(syntax, value, type);
    }

    private BoundExpression bindStringLiteralExpression(LiteralExpressionSyntax syntax) {
        TypeSymbol type = Types.STRING;
        Object value = syntax.getValueToken().getValue();
        return new BoundLiteralExpression(syntax, value, type);
    }

    private BoundExpression bindBooleanLiteralExpression(LiteralExpressionSyntax syntax) {
        TypeSymbol type = Types.BOOL;
        Object value = syntax.getValueToken().getKind() == SyntaxKind.TRUE_KEYWORD;
        return new BoundLiteralExpression(syntax, value, type);
    }

    private BoundExpression bindNumberLiteralExpression(LiteralExpressionSyntax syntax) {
        if (syntax.getValueToken().getValue() instanceof Integer) {
            TypeSymbol type = Types.INT;
            Object value = syntax.getValueToken().getValue();
            return new BoundLiteralExpression(syntax, value, type);
        } else if (syntax.getValueToken().getValue() instanceof Double) {
            TypeSymbol type = Types.DOUBLE;
            Object value = syntax.getValueToken().getValue();
            return new BoundLiteralExpression(syntax, value, type);
        }
        diagnostics.reportInvalidNumberLiteral(syntax.getValueToken().getLocation(), syntax.getValueToken().getText());
        return bindErrorExpression(syntax);
    }

    private BoundExpression bindErrorExpression(SyntaxNode syntax) {
        return new BoundErrorExpression(syntax);
    }

    private BoundExpression bindParenthesizedExpression(ParenthesizedExpressionSyntax syntax) {
        return bindExpression(syntax.getExpression());
    }

    private BoundStatement bindErrorStatement(SyntaxNode syntax) {
        return new BoundExpressionStatement(syntax, bindErrorExpression(syntax));
    }

    private TypeSymbol bindTypeClause(TypeClauseSyntax typeClause) {
        String typeName = typeClause.getTypeName().getText();
        if (typeClause instanceof ArrayTypeClauseSyntax) {
            return bindArrayTypeClause((ArrayTypeClauseSyntax) typeClause);
        }

        return getTypeSymbol(typeName);
    }

    private TypeSymbol getTypeSymbol(String typeName) {
        TypeSymbol type = null;

        if (programScope.isTypeDeclared(typeName)) {
            type = programScope.getType(typeName);
        } else if (programScope.isTypeImported(typeName)) {
            type = programScope.getType(typeName);
        } else if (currentType != null) {
            if (currentType.isGeneric(typeName)) {
                type = TypeSymbol.createGeneric(typeName);
            }
        } else if (scope instanceof BoundTypeScope typeScope) {
            if (typeScope.isGenericDeclared(typeName)) {
                type = TypeSymbol.createGeneric(typeName);
            }
        }
        return type;
    }

    private TypeSymbol bindArrayTypeClause(ArrayTypeClauseSyntax typeClause) {
        String typeName = typeClause.getTypeName().getText();

        TypeSymbol type = getTypeSymbol(typeName);

        if (type == null) {
            return null;
        }

        int rank = typeClause.getRank();
        for (int i = 0; i < rank; i++) {
            type = new ArrayTypeSymbol(type);
        }

        return type;
    }

    private boolean isReservedFieldName(String fieldName) {
        return fieldName.equals("toString");
    }

    private boolean isReservedTypeName(String typeName) {
        return false;
    }
}
