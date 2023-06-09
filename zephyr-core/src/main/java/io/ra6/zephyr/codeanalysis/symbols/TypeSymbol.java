package io.ra6.zephyr.codeanalysis.symbols;

import io.ra6.zephyr.builtin.Types;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TypeSymbol extends Symbol {

    @Setter
    private List<Symbol> fieldsAndFunctions = new ArrayList<>();

    @Setter
    private List<ConstructorSymbol> constructors = new ArrayList<>();

    @Setter
    private List<UnaryOperatorSymbol> unaryOperators = new ArrayList<>();

    @Setter
    private List<BinaryOperatorSymbol> binaryOperators = new ArrayList<>();

    @Setter
    private List<String> genericTypes = new ArrayList<>();

    @Getter
    private boolean isGeneric;

    public TypeSymbol(String name) {
        super(name);
    }

    public static TypeSymbol createGeneric(String name) {
        TypeSymbol symbol = new TypeSymbol(name);
        symbol.isGeneric = true;
        return symbol;
    }

    public boolean isBinaryOperatorDefined(String operator, TypeSymbol toSymbol) {
        return binaryOperators.stream().anyMatch(o -> o.getName().equals(operator) && o.getOtherType().equals(toSymbol));
    }

    @Override
    public SymbolKind getKind() {
        return SymbolKind.TYPE;
    }

    public TypeSymbol getBinaryOperatorType(String text, TypeSymbol rightType) {
        Optional<BinaryOperatorSymbol> symbol = binaryOperators.stream().filter(o -> o.getName().equals(text) && o.getOtherType().equals(rightType)).findFirst();
        if (symbol.isPresent()) {
            return symbol.get().getReturnType();
        }
        return Types.ERROR;
    }

    public boolean isUnaryOperatorDefined(String text) {
        return unaryOperators.stream().anyMatch(o -> o.getName().equals(text));
    }

    public TypeSymbol getUnaryOperatorType(String text) {
        Optional<UnaryOperatorSymbol> symbol = unaryOperators.stream().filter(o -> o.getName().equals(text)).findFirst();
        if (symbol.isPresent()) {
            return symbol.get().getReturnType();
        }
        return Types.ERROR;
    }

    public boolean isConstructorDefined(int size) {
        return constructors.stream().anyMatch(c -> c.getParameters().size() == size);
    }

    public ConstructorSymbol getConstructor(int size) {
        Optional<ConstructorSymbol> symbol = constructors.stream().filter(c -> c.getParameters().size() == size).findFirst();
        return symbol.orElse(null);
    }


    public boolean isFieldOrFunctionDeclared(String name) {
        return fieldsAndFunctions.stream().anyMatch(f -> f.getName().equals(name));
    }

    public boolean isField(String name) {
        return fieldsAndFunctions.stream().anyMatch(f -> f.getName().equals(name) && f.getKind() == SymbolKind.FIELD);
    }

    public boolean isFunction(String name) {
        return fieldsAndFunctions.stream().anyMatch(f -> f.getName().equals(name) && f.getKind() == SymbolKind.FUNCTION);
    }

    public FieldSymbol getField(String fieldName) {
        Optional<FieldSymbol> symbol = fieldsAndFunctions.stream().filter(f -> f instanceof FieldSymbol).map(f -> (FieldSymbol) f).filter(f -> f.getName().equals(fieldName)).findFirst();
        return symbol.orElse(null);
    }

    public boolean isFunctionDefined(String functionName, int size) {
        return fieldsAndFunctions.stream().filter(f -> f instanceof FunctionSymbol).anyMatch(f -> f.getName().equals(functionName) && ((FunctionSymbol) f).getParameters().size() == size);
    }

    public boolean isFunctionDefined(String functionName) {
        return fieldsAndFunctions.stream().filter(f -> f instanceof FunctionSymbol).anyMatch(f -> f.getName().equals(functionName));
    }

    public FunctionSymbol getFunction(String functionName, boolean isShared) {
        Optional<FunctionSymbol> symbol = fieldsAndFunctions.stream().filter(f -> f instanceof FunctionSymbol).map(f -> (FunctionSymbol) f).filter(f -> f.getName().equals(functionName) && f.isShared() == isShared).findFirst();
        return symbol.orElse(null);
    }

    public UnaryOperatorSymbol getUnaryOperator(String operator) {
        Optional<UnaryOperatorSymbol> symbol = unaryOperators.stream().filter(o -> o.getName().equals(operator)).findFirst();
        return symbol.orElse(null);
    }

    public BinaryOperatorSymbol getBinaryOperator(String operator, TypeSymbol rightType) {
        Optional<BinaryOperatorSymbol> symbol = binaryOperators.stream().filter(o -> o.getName().equals(operator) && o.getOtherType().equals(rightType)).findFirst();
        return symbol.orElse(null);
    }

    public Symbol getFieldOrFunction(String member) {
        Optional<Symbol> symbol = fieldsAndFunctions.stream().filter(f -> f.getName().equals(member)).findFirst();
        return symbol.orElse(null);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TypeSymbol) {
            return ((TypeSymbol) obj).getName().equals(getName());
        }
        return super.equals(obj);
    }

    public List<FieldSymbol> getFields() {
        List<FieldSymbol> fields = new ArrayList<>();
        for (Symbol symbol : fieldsAndFunctions) {
            if (symbol instanceof FieldSymbol) {
                fields.add((FieldSymbol) symbol);
            }
        }
        return fields;
    }

    public List<FunctionSymbol> getFunctions() {
        List<FunctionSymbol> functions = new ArrayList<>();
        for (Symbol symbol : fieldsAndFunctions) {
            if (symbol instanceof FunctionSymbol) {
                functions.add((FunctionSymbol) symbol);
            }
        }
        return functions;
    }

    public boolean isGeneric(String name) {
        return genericTypes.contains(name);
    }

    public int getGenericCount() {
        return genericTypes.size();
    }

    public String getGenericAt(int index) {
        return genericTypes.get(index);
    }

    public boolean isAssignableTo(TypeSymbol type) {
        if (type == null) {
            return false;
        }
        if (type.equals(this)) {
            return true;
        }
        if (type.equals(Types.ANY)) {
            return true;
        }
        return false;
    }
}
