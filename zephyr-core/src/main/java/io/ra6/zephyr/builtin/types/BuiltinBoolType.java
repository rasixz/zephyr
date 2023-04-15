package io.ra6.zephyr.builtin.types;

import io.ra6.zephyr.builtin.InternalBinaryOperator;
import io.ra6.zephyr.builtin.InternalUnaryOperator;
import io.ra6.zephyr.builtin.Types;
import io.ra6.zephyr.codeanalysis.binding.scopes.BoundTypeScope;
import io.ra6.zephyr.codeanalysis.symbols.BinaryOperatorSymbol;
import io.ra6.zephyr.codeanalysis.symbols.TypeSymbol;
import io.ra6.zephyr.codeanalysis.symbols.UnaryOperatorSymbol;

import static io.ra6.zephyr.builtin.IFunctionBase.PARAM_OTHER;
import static io.ra6.zephyr.builtin.IFunctionBase.PARAM_THIS;

public class BuiltinBoolType extends BuiltinType {
    private final BoundTypeScope typeScope = new BoundTypeScope(null, Types.INT);

    @Override
    protected void declareFields() {

    }

    @Override
    protected void defineFields() {

    }

    @Override
    protected void declareConstructors() {

    }

    @Override
    protected void defineConstructors() {

    }

    @Override
    protected void declareFunctions() {

    }

    @Override
    protected void defineFunctions() {

    }

    @Override
    protected void declareBinaryOperators() {
        typeScope.declareBinaryOperator(createBinaryOperator("==", Types.BOOL, Types.BOOL));
        typeScope.declareBinaryOperator(createBinaryOperator("!=", Types.BOOL, Types.BOOL));

        typeScope.declareBinaryOperator(createBinaryOperator("&&", Types.BOOL, Types.BOOL));
        typeScope.declareBinaryOperator(createBinaryOperator("||", Types.BOOL, Types.BOOL));
    }

    @Override
    protected void defineBinaryOperators() {
        for (BinaryOperatorSymbol symbol : typeScope.getDeclaredBinaryOperators()) {
            InternalBinaryOperator ibo = new InternalBinaryOperator(symbol.getName(), symbol.getOtherType(), symbol.getReturnType(), args -> {
                boolean selfValue = (boolean) args.get(PARAM_THIS);
                boolean otherValue = (boolean) args.get(PARAM_OTHER);

                return switch (symbol.getName()) {
                    case "==" -> selfValue == otherValue;
                    case "!=" -> selfValue != otherValue;
                    case "&&" -> selfValue && otherValue;
                    case "||" -> selfValue || otherValue;
                    default -> null;
                };
            });

            typeScope.defineBinaryOperator(ibo.getBinaryOperatorSymbol(), ibo.bindBody());
        }
    }

    @Override
    protected void declareUnaryOperators() {
        typeScope.declareUnaryOperator(createUnaryOperator("!", Types.BOOL));
    }

    @Override
    protected void defineUnaryOperators() {
        for (UnaryOperatorSymbol symbol : typeScope.getDeclaredUnaryOperators()) {
            InternalUnaryOperator iuo = new InternalUnaryOperator(symbol.getName(), symbol.getReturnType(), args -> {
                boolean selfValue = (boolean) args.get(PARAM_THIS);

                if (symbol.getName().equals("!")) {
                    return !selfValue;
                }

                throw new RuntimeException("Unknown unary operator: " + symbol.getName());
            });

            typeScope.defineUnaryOperator(iuo.getUnaryOperatorSymbol(), iuo.bindBody());
        }
    }

    @Override
    public TypeSymbol getTypeSymbol() {
        return Types.BOOL;
    }

    @Override
    public BoundTypeScope getTypeScope() {
        return typeScope;
    }
}
