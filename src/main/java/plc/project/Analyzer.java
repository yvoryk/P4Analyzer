package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * See the specification for information about what the different visit
 * methods should do.
 */
public final class Analyzer implements Ast.Visitor<Void> {
    public Scope scope;
    private Ast.Method method;
    private Environment.Type currentReturnType;

    public Analyzer(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", "System.out.println", Arrays.asList(Environment.Type.ANY), Environment.Type.NIL, args -> Environment.NIL);
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Void visit(Ast.Source ast) {
        // Visit all fields
        for (Ast.Field field : ast.getFields()) {
            visit(field);
        }
        
        // Visit all methods
        for (Ast.Method method : ast.getMethods()) {
            visit(method);
        }
        
        // Check that main/0 function exists
        Environment.Function mainFunction;
        try {
            mainFunction = scope.lookupFunction("main", 0);
        } catch (RuntimeException e) {
            throw new RuntimeException("main/0 function does not exist");
        }
        
        // Check that main/0 has Integer return type
        if (!mainFunction.getReturnType().equals(Environment.Type.INTEGER)) {
            throw new RuntimeException("main/0 function must have Integer return type");
        }
        
        return null;
    }

    @Override
    public Void visit(Ast.Field ast) {
        // Visit the value if present (before defining the variable)
        Environment.Type fieldType;
        try {
            fieldType = Environment.getType(ast.getTypeName());
        } catch (RuntimeException e) {
            throw new RuntimeException("Unknown type: " + ast.getTypeName());
        }
        
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
            // Check that value is assignable to field type
            requireAssignable(fieldType, ast.getValue().get().getType());
        } else {
            // Constant field must have an initial value
            if (ast.getConstant()) {
                throw new RuntimeException("Constant field must have an initial value");
            }
        }
        
        // Define the variable
        Environment.Variable variable = scope.defineVariable(
            ast.getName(),
            ast.getName(),
            fieldType,
            ast.getConstant(),
            Environment.NIL
        );
        
        ast.setVariable(variable);
        
        return null;
    }

    @Override
    public Void visit(Ast.Method ast) {
        // Get parameter types
        List<Environment.Type> parameterTypes = ast.getParameterTypeNames().stream()
            .map(typeName -> {
                try {
                    return Environment.getType(typeName);
                } catch (RuntimeException e) {
                    throw new RuntimeException("Unknown type: " + typeName);
                }
            })
            .collect(Collectors.toList());
        
        // Get return type
        Environment.Type returnType;
        if (ast.getReturnTypeName().isPresent()) {
            try {
                returnType = Environment.getType(ast.getReturnTypeName().get());
            } catch (RuntimeException e) {
                throw new RuntimeException("Unknown type: " + ast.getReturnTypeName().get());
            }
        } else {
            returnType = Environment.Type.NIL;
        }
        
        // Define the function
        Environment.Function function = scope.defineFunction(
            ast.getName(),
            ast.getName(),
            parameterTypes,
            returnType,
            args -> Environment.NIL
        );
        
        ast.setFunction(function);
        
        // Save current return type for return statements
        Environment.Type previousReturnType = currentReturnType;
        currentReturnType = returnType;
        
        // Create new scope for method body with parameters
        Scope previousScope = scope;
        scope = new Scope(previousScope);
        
        // Define parameters as variables
        for (int i = 0; i < ast.getParameters().size(); i++) {
            scope.defineVariable(
                ast.getParameters().get(i),
                ast.getParameters().get(i),
                parameterTypes.get(i),
                false,
                Environment.NIL
            );
        }
        
        // Visit statements
        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }
        
        // Restore scope and return type
        scope = previousScope;
        currentReturnType = previousReturnType;
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        // Expression must be a function call
        if (!(ast.getExpression() instanceof Ast.Expression.Function)) {
            throw new RuntimeException("Expression statement must be a function call");
        }
        
        visit(ast.getExpression());
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        // Visit value if present (before defining variable)
        Environment.Type varType = null;
        
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
            varType = ast.getValue().get().getType();
        }
        
        // Determine type
        if (ast.getTypeName().isPresent()) {
            try {
                Environment.Type declaredType = Environment.getType(ast.getTypeName().get());
                
                // If we also have a value, check assignability
                if (varType != null) {
                    requireAssignable(declaredType, varType);
                }
                
                varType = declaredType;
            } catch (RuntimeException e) {
                throw new RuntimeException("Unknown type: " + ast.getTypeName().get());
            }
        }
        
        // Check that we have a type
        if (varType == null) {
            throw new RuntimeException("Variable declaration must have a type or initial value");
        }
        
        // Define the variable
        Environment.Variable variable = scope.defineVariable(
            ast.getName(),
            ast.getName(),
            varType,
            false,
            Environment.NIL
        );
        
        ast.setVariable(variable);
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        // Receiver must be an access expression
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) {
            throw new RuntimeException("Assignment receiver must be an access expression");
        }
        
        // Visit both sides
        visit(ast.getReceiver());
        visit(ast.getValue());
        
        // Check that we're not assigning to a constant
        Ast.Expression.Access access = (Ast.Expression.Access) ast.getReceiver();
        if (access.getVariable().getConstant()) {
            throw new RuntimeException("Cannot assign to constant field");
        }
        
        // Check assignability
        requireAssignable(ast.getReceiver().getType(), ast.getValue().getType());
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        // Visit condition
        visit(ast.getCondition());
        
        // Check that condition is boolean
        if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("If condition must be of type Boolean");
        }
        
        // Check that then statements are not empty
        if (ast.getThenStatements().isEmpty()) {
            throw new RuntimeException("If statement must have at least one then statement");
        }
        
        // Visit then statements in new scope
        Scope previousScope = scope;
        scope = new Scope(previousScope);
        for (Ast.Statement statement : ast.getThenStatements()) {
            visit(statement);
        }
        scope = previousScope;
        
        // Visit else statements in new scope if present
        if (!ast.getElseStatements().isEmpty()) {
            scope = new Scope(previousScope);
            for (Ast.Statement statement : ast.getElseStatements()) {
                visit(statement);
            }
            scope = previousScope;
        }
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.For ast) {
        // Check that statements list is not empty
        if (ast.getStatements().isEmpty()) {
            throw new RuntimeException("For statement must have at least one statement");
        }
        
        // Visit initialization if present
        if (ast.getInitialization() != null) {
            visit(ast.getInitialization());
            
            // If initialization is a declaration or assignment, check that it's Comparable
            if (ast.getInitialization() instanceof Ast.Statement.Declaration) {
                Ast.Statement.Declaration decl = (Ast.Statement.Declaration) ast.getInitialization();
                Environment.Type varType = decl.getVariable().getType();
                // Check if it's comparable
                requireAssignable(Environment.Type.COMPARABLE, varType);
            } else if (ast.getInitialization() instanceof Ast.Statement.Assignment) {
                Ast.Statement.Assignment assign = (Ast.Statement.Assignment) ast.getInitialization();
                requireAssignable(Environment.Type.COMPARABLE, assign.getReceiver().getType());
            }
        }
        
        // Visit condition
        visit(ast.getCondition());
        
        // Check that condition is boolean
        if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("For condition must be of type Boolean");
        }
        
        // Visit increment if present
        if (ast.getIncrement() != null) {
            visit(ast.getIncrement());
            
            // Check that increment expression type matches initialization type
            if (ast.getInitialization() != null && ast.getIncrement() instanceof Ast.Statement.Assignment) {
                Ast.Statement.Assignment increment = (Ast.Statement.Assignment) ast.getIncrement();
                
                if (ast.getInitialization() instanceof Ast.Statement.Declaration) {
                    Ast.Statement.Declaration decl = (Ast.Statement.Declaration) ast.getInitialization();
                    if (!increment.getReceiver().getType().equals(decl.getVariable().getType())) {
                        throw new RuntimeException("For increment type must match initialization type");
                    }
                } else if (ast.getInitialization() instanceof Ast.Statement.Assignment) {
                    Ast.Statement.Assignment init = (Ast.Statement.Assignment) ast.getInitialization();
                    if (!increment.getReceiver().getType().equals(init.getReceiver().getType())) {
                        throw new RuntimeException("For increment type must match initialization type");
                    }
                }
            }
        }
        
        // Visit statements in new scope
        Scope previousScope = scope;
        scope = new Scope(previousScope);
        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }
        scope = previousScope;
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        // Visit condition
        visit(ast.getCondition());
        
        // Check that condition is boolean
        if (!ast.getCondition().getType().equals(Environment.Type.BOOLEAN)) {
            throw new RuntimeException("While condition must be of type Boolean");
        }
        
        // Visit statements in new scope
        Scope previousScope = scope;
        scope = new Scope(previousScope);
        for (Ast.Statement statement : ast.getStatements()) {
            visit(statement);
        }
        scope = previousScope;
        
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        // Visit the return value
        visit(ast.getValue());
        
        // Check that return value is assignable to function return type
        if (currentReturnType == null) {
            throw new RuntimeException("Return statement outside of function");
        }
        
        requireAssignable(currentReturnType, ast.getValue().getType());
        
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object literal = ast.getLiteral();
        
        if (literal == null) {
            // NIL
            ast.setType(Environment.Type.NIL);
        } else if (literal instanceof Boolean) {
            ast.setType(Environment.Type.BOOLEAN);
        } else if (literal instanceof Character) {
            ast.setType(Environment.Type.CHARACTER);
        } else if (literal instanceof String) {
            ast.setType(Environment.Type.STRING);
        } else if (literal instanceof BigInteger) {
            BigInteger value = (BigInteger) literal;
            // Check range for int (32-bit signed)
            if (value.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0 ||
                value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new RuntimeException("Integer literal out of range");
            }
            ast.setType(Environment.Type.INTEGER);
        } else if (literal instanceof BigDecimal) {
            BigDecimal value = (BigDecimal) literal;
            // Check range for double
            double doubleValue = value.doubleValue();
            if (Double.isInfinite(doubleValue)) {
                throw new RuntimeException("Decimal literal out of range");
            }
            ast.setType(Environment.Type.DECIMAL);
        } else {
            throw new RuntimeException("Unknown literal type");
        }
        
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        // Expression must be a binary expression
        if (!(ast.getExpression() instanceof Ast.Expression.Binary)) {
            throw new RuntimeException("Group expression must contain a binary expression");
        }
        
        visit(ast.getExpression());
        ast.setType(ast.getExpression().getType());
        
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        // Visit both operands
        visit(ast.getLeft());
        visit(ast.getRight());
        
        String operator = ast.getOperator();
        Environment.Type leftType = ast.getLeft().getType();
        Environment.Type rightType = ast.getRight().getType();
        
        switch (operator) {
            case "AND":
            case "OR":
                // Both operands must be Boolean
                if (!leftType.equals(Environment.Type.BOOLEAN) || !rightType.equals(Environment.Type.BOOLEAN)) {
                    throw new RuntimeException("Logical operators require Boolean operands");
                }
                ast.setType(Environment.Type.BOOLEAN);
                break;
                
            case "<":
            case "<=":
            case ">":
            case ">=":
            case "==":
            case "!=":
                // Both operands must be Comparable and same type
                requireAssignable(Environment.Type.COMPARABLE, leftType);
                requireAssignable(Environment.Type.COMPARABLE, rightType);
                if (!leftType.equals(rightType)) {
                    throw new RuntimeException("Comparison operators require operands of same type");
                }
                ast.setType(Environment.Type.BOOLEAN);
                break;
                
            case "+":
                // If either side is String, result is String
                if (leftType.equals(Environment.Type.STRING) || rightType.equals(Environment.Type.STRING)) {
                    ast.setType(Environment.Type.STRING);
                } else {
                    // Otherwise, both must be Integer or Decimal and same type
                    if (!leftType.equals(Environment.Type.INTEGER) && !leftType.equals(Environment.Type.DECIMAL)) {
                        throw new RuntimeException("Addition requires Integer, Decimal, or String operands");
                    }
                    if (!leftType.equals(rightType)) {
                        throw new RuntimeException("Arithmetic operators require operands of same type");
                    }
                    ast.setType(leftType);
                }
                break;
                
            case "-":
            case "*":
            case "/":
                // Both must be Integer or Decimal and same type
                if (!leftType.equals(Environment.Type.INTEGER) && !leftType.equals(Environment.Type.DECIMAL)) {
                    throw new RuntimeException("Arithmetic operators require Integer or Decimal operands");
                }
                if (!leftType.equals(rightType)) {
                    throw new RuntimeException("Arithmetic operators require operands of same type");
                }
                ast.setType(leftType);
                break;
                
            default:
                throw new RuntimeException("Unknown binary operator: " + operator);
        }
        
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        if (ast.getReceiver().isPresent()) {
            // Field access
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();
            Environment.Variable field = receiverType.getField(ast.getName());
            ast.setVariable(field);
        } else {
            // Variable access
            Environment.Variable variable = scope.lookupVariable(ast.getName());
            ast.setVariable(variable);
        }
        
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        // Visit all arguments first
        for (Ast.Expression argument : ast.getArguments()) {
            visit(argument);
        }
        
        Environment.Function function;
        
        if (ast.getReceiver().isPresent()) {
            // Method call
            visit(ast.getReceiver().get());
            Environment.Type receiverType = ast.getReceiver().get().getType();
            function = receiverType.getFunction(ast.getName(), ast.getArguments().size());
            
            // Check argument types (skip first parameter which is the receiver)
            for (int i = 0; i < ast.getArguments().size(); i++) {
                requireAssignable(
                    function.getParameterTypes().get(i + 1),
                    ast.getArguments().get(i).getType()
                );
            }
        } else {
            // Function call
            function = scope.lookupFunction(ast.getName(), ast.getArguments().size());
            
            // Check argument types
            for (int i = 0; i < ast.getArguments().size(); i++) {
                requireAssignable(
                    function.getParameterTypes().get(i),
                    ast.getArguments().get(i).getType()
                );
            }
        }
        
        ast.setFunction(function);
        
        return null;
    }

    public static void requireAssignable(Environment.Type target, Environment.Type type) {
        // Same type is always assignable
        if (target.equals(type)) {
            return;
        }
        
        // Any can accept any type
        if (target.equals(Environment.Type.ANY)) {
            return;
        }
        
        // Comparable can accept Integer, Decimal, Character, or String
        if (target.equals(Environment.Type.COMPARABLE)) {
            if (type.equals(Environment.Type.INTEGER) ||
                type.equals(Environment.Type.DECIMAL) ||
                type.equals(Environment.Type.CHARACTER) ||
                type.equals(Environment.Type.STRING)) {
                return;
            }
        }
        
        // Otherwise, not assignable
        throw new RuntimeException("Type " + type.getName() + " is not assignable to " + target.getName());
    }

}
