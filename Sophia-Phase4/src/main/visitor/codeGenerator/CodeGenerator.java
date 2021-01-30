package main.visitor.codeGenerator;

import main.ast.nodes.Program;
import main.ast.nodes.declaration.classDec.ClassDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.ConstructorDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.FieldDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.MethodDeclaration;
import main.ast.nodes.declaration.variableDec.VarDeclaration;
import main.ast.nodes.expression.*;
import main.ast.nodes.expression.operators.BinaryOperator;
import main.ast.nodes.expression.operators.UnaryOperator;
import main.ast.nodes.expression.values.ListValue;
import main.ast.nodes.expression.values.NullValue;
import main.ast.nodes.expression.values.primitive.BoolValue;
import main.ast.nodes.expression.values.primitive.IntValue;
import main.ast.nodes.expression.values.primitive.StringValue;
import main.ast.nodes.statement.*;
import main.ast.nodes.statement.loop.BreakStmt;
import main.ast.nodes.statement.loop.ContinueStmt;
import main.ast.nodes.statement.loop.ForStmt;
import main.ast.nodes.statement.loop.ForeachStmt;
import main.ast.types.NullType;
import main.ast.types.Type;
import main.ast.types.functionPointer.FptrType;
import main.ast.types.list.ListNameType;
import main.ast.types.list.ListType;
import main.ast.types.single.BoolType;
import main.ast.types.single.ClassType;
import main.ast.types.single.IntType;
import main.ast.types.single.StringType;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.ItemNotFoundException;
import main.symbolTable.items.ClassSymbolTableItem;
import main.symbolTable.items.FieldSymbolTableItem;
import main.symbolTable.utils.graph.Graph;
import main.visitor.IVisitor;
import main.visitor.Visitor;
import main.visitor.typeChecker.ExpressionTypeChecker;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class CodeGenerator extends Visitor<String> {
    ExpressionTypeChecker expressionTypeChecker;
    Graph<String> classHierarchy;
    private String outputPath;
    private FileWriter currentFile;
    private ClassDeclaration currentClass;
    private MethodDeclaration currentMethod;
    private int methodTempVariables;
    private int label;
    private String breakLabel;
    private String continueLabel;

    public CodeGenerator(Graph<String> classHierarchy) {
        this.classHierarchy = classHierarchy;
        this.expressionTypeChecker = new ExpressionTypeChecker(classHierarchy);
        this.prepareOutputFolder();
    }

    private void prepareOutputFolder() {
        this.outputPath = "output/";
        String jasminPath = "utilities/jarFiles/jasmin.jar";
        String listClassPath = "utilities/codeGenerationUtilityClasses/List.j";
        String fptrClassPath = "utilities/codeGenerationUtilityClasses/Fptr.j";
        try{
            File directory = new File(this.outputPath);
            File[] files = directory.listFiles();
            if(files != null)
                for (File file : files)
                    file.delete();
            directory.mkdir();
        }
        catch(SecurityException e) { }
        copyFile(jasminPath, this.outputPath + "jasmin.jar");
        copyFile(listClassPath, this.outputPath + "List.j");
        copyFile(fptrClassPath, this.outputPath + "Fptr.j");
    }

    private void copyFile(String toBeCopied, String toBePasted) {
        try {
            File readingFile = new File(toBeCopied);
            File writingFile = new File(toBePasted);
            InputStream readingFileStream = new FileInputStream(readingFile);
            OutputStream writingFileStream = new FileOutputStream(writingFile);
            byte[] buffer = new byte[1024];
            int readLength;
            while ((readLength = readingFileStream.read(buffer)) > 0)
                writingFileStream.write(buffer, 0, readLength);
            readingFileStream.close();
            writingFileStream.close();
        } catch (IOException e) { }
    }

    private void createFile(String name) {
        try {
            String path = this.outputPath + name + ".j";
            File file = new File(path);
            file.createNewFile();
            FileWriter fileWriter = new FileWriter(path);
            this.currentFile = fileWriter;
        } catch (IOException e) {}
    }

    private void addCommand(String command) {
        try {
            command = String.join("\n\t\t", command.split("\n"));
            if(command.startsWith("Label_"))
                this.currentFile.write("\t" + command + "\n");
            else if(command.startsWith("."))
                this.currentFile.write(command + "\n");
            else
                this.currentFile.write("\t\t" + command + "\n");
            this.currentFile.flush();
        } catch (IOException e) {}
    }

    private String getLabel() {
        String label = "Label_" + this.label;
        this.label += 1;
        return label;
    }

    private String addDefaultFieldValueToClass(Type fieldType) {
        if (fieldType instanceof IntType) {
            return "ldc 0";
        } else if (fieldType instanceof StringType) {
            return  "ldc \"\"";
        } else if (fieldType instanceof  BoolType) {
            return  "ldc 0";
        } else if ((fieldType instanceof ClassType) || fieldType instanceof FptrType) {
            return "ldc null\n";
        } else if (fieldType instanceof ListType) {
            ListType listType = (ListType)fieldType;
            String commands = "";
            commands += "new List\n";
            commands += "dup\n";

            commands += "new java/util/ArrayList\n";
            commands += "dup\n";
            commands += "invokespecial java/util/ArrayList/<init>()V\n";

            for (ListNameType listNameType : listType.getElementsTypes()) {
                commands += this.addDefaultFieldValueToClass(listNameType.getType());
                if (listNameType.getType() instanceof IntType) {
                    commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
                } else if (listNameType.getType() instanceof BoolType) {
                    commands += "invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;\n";
                }
                commands += "checkcast java/lang/Object\n";
                commands += "invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n";
            }
            commands += "invokespecial List/<init>(Ljava/util/ArrayList;)V\n";
            return commands;
        }
        return "";
    }

    private String makeTypeSignature(Type fieldType) {
        String typeString = "";
        if (fieldType instanceof IntType) {
            typeString = "java/lang/Integer";
        } else if (fieldType instanceof BoolType) {
            typeString = "java/lang/Boolean";
        } else if (fieldType instanceof  StringType) {
            typeString = "java/lang/String";
        } else if (fieldType instanceof ListType) {
            typeString = "List";
        } else if (fieldType instanceof FptrType) {
            typeString = "Fptr";
        } else if (fieldType instanceof ClassType) {
            typeString = this.currentClass.getClassName().getName();
        }
        return "L" + typeString + ";";
    }

    private void addDefaultConstructor() {
        this.addCommand(".method public <init>()V\n");
        this.addCommand(".limit locals 128\n");
        this.addCommand(".limit stack 128\n");
        //TODO:cons
//        String objectCreation = "";
//        objectCreation += "aload_0\n";
//        objectCreation += "invokespecial java/lang/Object/<init>()V\n";
//        this.addCommand(objectCreation);

        if(this.currentClass.getParentClassName() != null) { // Todo: Check this
            String objectParentCreation = "";
            objectParentCreation += "aload_0\n";
            objectParentCreation += "invokespecial " + this.currentClass.getParentClassName().getName() + "/" + "<init>()V\n";
            this.addCommand(objectParentCreation);
        } else {
            String objectParentCreation = "";
            objectParentCreation += "aload_0\n";
            objectParentCreation += "invokespecial java/lang/Object/" + "<init>()V\n";
            this.addCommand(objectParentCreation);
        }

        String commands = "";
        for (FieldDeclaration fieldDeclaration : this.currentClass.getFields()) {
            commands += "aload_0\n";
            Type fieldType = fieldDeclaration.accept(this.expressionTypeChecker);
            String fieldName = fieldDeclaration.getVarDeclaration().getVarName().getName();
            commands += this.addDefaultFieldValueToClass(fieldType) + "\n";
            commands += "putfield " + this.currentClass.getClassName().getName() + "/" + fieldName + " "
                    + makeTypeSignature(fieldType) + "\n";
        }
        this.addCommand(commands);

        this.addCommand("return\n");
        this.addCommand(".end method\n");
    }

    private void addStaticMainMethod() {
        this.addCommand(".method public static main([Ljava/lang/String;)V\n");
        this.addCommand(".limit locals 128\n");
        this.addCommand(".limit stack 128\n");

        this.addCommand("new Main\n");
        this.addCommand("dup\n");
        this.addCommand("invokespecial Main/<init>()V\n");
        this.addCommand("return\n");
        this.addCommand(".end method\n");
    }

    private int slotOf(String identifier) {
        if (identifier == "") {
            this.methodTempVariables += 1;
            return this.currentMethod.getArgs().size() + this.currentMethod.getLocalVars().size() + this.methodTempVariables;
        } else {
            int index = 1;
            for (VarDeclaration varDeclaration : this.currentMethod.getArgs()) {
                if (identifier.equals(varDeclaration.getVarName().getName())) {
                    return index;
                }
                index += 1;
            }
            for (VarDeclaration varDeclaration : this.currentMethod.getLocalVars()) {
                if ((identifier.equals(varDeclaration.getVarName().getName()))) {
                    return index;
                }
                index += 1;
            }
        }
        return -1;
    }

    @Override
    public String visit(Program program) {
        for (ClassDeclaration classDeclaration : program.getClasses()) {
            this.currentClass = classDeclaration;
            this.expressionTypeChecker.setCurrentClass(classDeclaration);
            classDeclaration.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ClassDeclaration classDeclaration) {
        this.createFile(classDeclaration.getClassName().getName());
        this.label = 0;

        this.addCommand(".class public " + classDeclaration.getClassName().getName() + '\n');

        String superHeader;
        if (classDeclaration.getParentClassName() == null) {
            superHeader = ".super java/lang/Object";
        } else {
            superHeader = ".super " + classDeclaration.getParentClassName().getName();
        }
        superHeader += '\n';
        this.addCommand(superHeader);

        for (FieldDeclaration fieldDeclaration : classDeclaration.getFields()) {
            fieldDeclaration.accept(this);
        }

        if (classDeclaration.getConstructor() != null) {
            if (classDeclaration.getConstructor().getArgs().size() > 0) {
                this.addDefaultConstructor();
            }
            this.currentMethod = classDeclaration.getConstructor();
            this.expressionTypeChecker.setCurrentMethod(classDeclaration.getConstructor());
            this.methodTempVariables = 0;
            classDeclaration.getConstructor().accept(this);
        } else {
            this.addDefaultConstructor();
        }

        for (MethodDeclaration methodDeclaration : classDeclaration.getMethods()) {
            this.currentMethod = methodDeclaration;
            this.expressionTypeChecker.setCurrentMethod(methodDeclaration);
            this.methodTempVariables = 0;
            methodDeclaration.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ConstructorDeclaration constructorDeclaration) {
        if (this.currentClass.getClassName().getName().equals("Main")) {
            this.addStaticMainMethod();
        }
        this.visit((MethodDeclaration) constructorDeclaration);
        return null;
    }

    @Override
    public String visit(MethodDeclaration methodDeclaration) {
        if (this.currentClass.getClassName().getName().equals(methodDeclaration.getMethodName().getName())) {
            String methodArgs = "";
            for (VarDeclaration varDeclaration : this.currentClass.getConstructor().getArgs()) {
                methodArgs += this.makeTypeSignature(varDeclaration.getType());
            }
            this.addCommand(".method public <init>(" + methodArgs + ")V\n");
            this.addCommand(".limit locals 128\n");
            this.addCommand(".limit stack 128\n");
            //TODO:cons
//            String objectCreation = "";
//            objectCreation += "aload_0\n";
//            objectCreation += "invokespecial " + this.currentClass.getClassName().getName() + "/<init>(" + methodArgs + ")V\n";
//            this.addCommand(objectCreation);

            if(this.currentClass.getParentClassName() != null) { // Todo: Check this
                String objectParentCreation = "";
                objectParentCreation += "aload_0\n";
                objectParentCreation += "invokespecial " + this.currentClass.getParentClassName().getName() + "/" + "<init>()V\n";
                this.addCommand(objectParentCreation);
            }else{
                String objectParentCreation = "";
                objectParentCreation += "aload_0\n";
                objectParentCreation += "invokespecial java/lang/Object/" + "<init>()V\n";
                this.addCommand(objectParentCreation);
            }

            String commands = "";
            for (FieldDeclaration fieldDeclaration : this.currentClass.getFields()) {
                commands += "aload_0\n";
                Type fieldType = fieldDeclaration.accept(this.expressionTypeChecker);
                String fieldName = fieldDeclaration.getVarDeclaration().getVarName().getName();
                commands += this.addDefaultFieldValueToClass(fieldType) + "\n";
                commands += "putfield " + this.currentClass.getClassName().getName() + "/" + fieldName + " "
                        + makeTypeSignature(fieldType) + "\n";
            }
            this.addCommand(commands);

            for (VarDeclaration varDeclaration : methodDeclaration.getLocalVars()) {
                varDeclaration.accept(this);
            }

            for (Statement statement : methodDeclaration.getBody()) {
                statement.accept(this);
            }

            this.addCommand("return\n");
            this.addCommand(".end method\n");
        } else {
            String methodArgs = "";
            for (VarDeclaration varDeclaration : this.currentClass.getConstructor().getArgs()) {
                methodArgs += this.makeTypeSignature(varDeclaration.getType());
            }
            String methodReturn = "";
            methodReturn += this.makeTypeSignature(methodDeclaration.getReturnType());
            this.addCommand(".method public <init>(" + methodArgs + ")" + methodReturn + "\n");
            this.addCommand(".limit locals 128\n");
            this.addCommand(".limit stack 128\n");

            for (VarDeclaration varDeclaration : methodDeclaration.getLocalVars()) {
                varDeclaration.accept(this);
            }

            for (Statement statement : methodDeclaration.getBody()) {
                statement.accept(this);
            }

            if (!(methodDeclaration.getDoesReturn())) {
                this.addCommand("return\n");
            }
            this.addCommand(".end method\n");
        }
        return null;
    }

    @Override
    public String visit(FieldDeclaration fieldDeclaration) {
        String fieldHeader = ".field public " + fieldDeclaration.getVarDeclaration().getVarName().getName() +
                " " + makeTypeSignature(fieldDeclaration.getVarDeclaration().getType());
        this.addCommand(fieldHeader);
        return null;
    }

    @Override
    public String visit(VarDeclaration varDeclaration) {
        String command = "";
        command += this.addDefaultFieldValueToClass(varDeclaration.getType()) + "\n";
        if (varDeclaration.getType() instanceof IntType ||
                varDeclaration.getType() instanceof BoolType) {
            command += "istore " + this.slotOf(varDeclaration.getVarName().getName()) + "\n";
        } else {
            command += "astore " + this.slotOf(varDeclaration.getVarName().getName()) + "\n";
        }
        this.addCommand(command);
        return null;
    }

    @Override
    public String visit(AssignmentStmt assignmentStmt) {
        BinaryExpression binaryExpression =
                new BinaryExpression(assignmentStmt.getlValue(), assignmentStmt.getrValue(), BinaryOperator.assign);
        this.addCommand(binaryExpression.accept(this));
        // Todo: Pop assignment expression value from stack
        //  this.addCommand("pop\n");
        return null;
    }

    @Override
    public String visit(BlockStmt blockStmt) {
        for (Statement statement : blockStmt.getStatements()) {
            statement.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ConditionalStmt conditionalStmt) {
        this.addCommand(conditionalStmt.getCondition().accept(this));

        String elseLabel = this.getLabel();
        String AfterLabel = this.getLabel();

        this.addCommand("if_eq " + elseLabel + "\n");
        conditionalStmt.getThenBody().accept(this);
        this.addCommand("goto " + AfterLabel + "\n");
        this.addCommand(elseLabel);
        conditionalStmt.getElseBody().accept(this);
        this.addCommand(AfterLabel);
        return null;
    }

    @Override
    public String visit(MethodCallStmt methodCallStmt) {
        this.expressionTypeChecker.setIsInMethodCallStmt(true);
        this.addCommand(methodCallStmt.getMethodCall().accept(this));
        this.expressionTypeChecker.setIsInMethodCallStmt(false);
        // Todo: Add pop
        //  this.addCommand("pop\n");
        return null;
    }

    @Override
    public String visit(PrintStmt print) {
        Type printType = print.getArg().accept(this.expressionTypeChecker);
        this.addCommand("getstatic java/lang/System/out Ljava/io/PrintStream;\n");
        if (print.getArg() != null) {
            this.addCommand(print.getArg().accept(this));
            //TODO:Casting
//            this.addCommand("invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n");
        }
        this.addCommand("invokevirtual java/io/PrintStream/print(" + this.makeTypeSignature(printType) + ")V");
        return null;
    }

    @Override
    public String visit(ReturnStmt returnStmt) {
        if (returnStmt.getReturnedExpr() == null) {
            this.addCommand("return\n");
        } else {
            returnStmt.getReturnedExpr().accept(this);
            if (this.currentMethod.getReturnType() instanceof IntType ||
                    this.currentMethod.getReturnType() instanceof BoolType) {
                this.addCommand("ireturn\n");
            } else {
                this.addCommand("areturn\n");
            }
        }
        return null;
    }

    @Override
    public String visit(BreakStmt breakStmt) {
        this.addCommand("goto " + this.breakLabel + "\n");
        return null;
    }

    @Override
    public String visit(ContinueStmt continueStmt) {
        this.addCommand("goto " + this.continueLabel + "\n");
        return null;
    }

    @Override
    public String visit(ForeachStmt foreachStmt) {
        String prevContinueLabel = this.continueLabel;
        String prevBreakLabel = this.breakLabel;
        this.continueLabel = this.getLabel();
        this.breakLabel = this.getLabel();

        int loop =  this.slotOf("");

        this.addCommand("ldc 0\n");
        this.addCommand("istore " + loop + "\n");

        this.addCommand(this.continueLabel);

        this.addCommand("iload " + loop+ "\n");
        Type type = foreachStmt.getList().accept(this.expressionTypeChecker);
        ListType listType = (ListType)type;
        this.addCommand("ldc " +  listType.getElementsTypes().size());
        this.addCommand("if_icmpge " + this.breakLabel);

        foreachStmt.getBody().accept(this);

        this.addCommand("iinc " + loop + " 1" + "\n");

        this.addCommand("goto " + this.continueLabel + "\n");

        this.addCommand(this.breakLabel);

        this.continueLabel = prevContinueLabel;
        this.breakLabel = prevBreakLabel;
        return null;
    }

    @Override
    public String visit(ForStmt forStmt) {
        String prevContinueLabel = this.continueLabel;
        String prevBreakLabel = this.breakLabel;
        this.continueLabel = this.getLabel();
        this.breakLabel = this.getLabel();

        if (forStmt.getInitialize() != null) {
            forStmt.getInitialize().accept(this);
        }
        this.addCommand(this.continueLabel);
        if (forStmt.getCondition() != null) { // Todo: Check this part
            forStmt.getCondition().accept(this);
        }
        forStmt.getBody().accept(this);
        if (forStmt.getUpdate() != null) {
            forStmt.getUpdate().accept(this);
        }
        this.addCommand("goto " + this.continueLabel + "\n");
        this.addCommand(this.breakLabel);

        this.continueLabel = prevContinueLabel;
        this.breakLabel = prevBreakLabel;
        return null;
    }

    @Override
    public String visit(BinaryExpression binaryExpression) {
        BinaryOperator operator = binaryExpression.getBinaryOperator();
        String commands = "";
        if (operator == BinaryOperator.add) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            commands += "iadd\n";
        }
        else if (operator == BinaryOperator.sub) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            commands += "isub\n";
        }
        else if (operator == BinaryOperator.mult) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            commands += "imul\n";
        }
        else if (operator == BinaryOperator.div) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            commands += "idiv\n";
        }
        else if (operator == BinaryOperator.mod) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            commands += "irem\n";
        }
        else if((operator == BinaryOperator.gt) || (operator == BinaryOperator.lt)) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            String trueLabel = this.getLabel();
            String afterLabel = this.getLabel();

            if ((operator == BinaryOperator.gt)) {
                commands += "If_icmpgt " + trueLabel + "\n";
            } else {
                commands += "If_icmplt " + trueLabel + "\n";
            }
            commands += "ldc 0\n";
            commands += "goto " + afterLabel + "\n";
            commands += trueLabel;
            commands += "ldc 1\n";
            commands += afterLabel;
        }
        else if((operator == BinaryOperator.eq) || (operator == BinaryOperator.neq)) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            String trueLabel = this.getLabel();
            String afterLabel = this.getLabel();

            if ((operator == BinaryOperator.eq)) {
                commands += "If_icmpeq " + trueLabel + "\n";
            } else {
                commands += "If_icmpne " + trueLabel + "\n";
            }
            commands += "ldc 0\n";
            commands += "goto " + afterLabel + "\n";
            commands += trueLabel;
            commands += "ldc 1\n";
            commands += afterLabel;
        }
        else if(operator == BinaryOperator.and) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            String trueLabel = this.getLabel();
            String afterLabel = this.getLabel();

            String nextLabel = this.getLabel();
            commands += "ldc 0\n";
            commands += "If_icmpne " + nextLabel + "\n";
            commands += "pop\n";
            commands += "ldc 0\n";
            commands += "goto " + afterLabel + "\n";
            commands += nextLabel + "\n";
            commands += "ldc 0\n";
            commands += "If_icmpne " + trueLabel + "\n";
            commands += "ldc 0\n";
            commands += "goto " + afterLabel + "\n";
            commands += trueLabel + "\n";
            commands += "ldc 1\n";
            commands += afterLabel;
        }
        else if(operator == BinaryOperator.or) {
            commands += binaryExpression.getFirstOperand().accept(this) + "\n";
            commands += binaryExpression.getSecondOperand().accept(this) + "\n";
            String trueLabel = this.getLabel();
            String afterLabel = this.getLabel();

            String secondTrueLabel = this.getLabel();
            commands += "ldc 0\n";
            commands += "If_icmpne " + trueLabel + "\n";
            commands += "ldc 0\n";
            commands += "If_icmpne " + secondTrueLabel + "\n";
            commands += "ldc 0\n";
            commands += "goto " + afterLabel + "\n";
            commands += trueLabel + "\n";
            commands += "pop\n";
            commands += "ldc 1\n";
            commands += "goto " + afterLabel;
            commands += secondTrueLabel + "\n";
            commands += "ldc 1\n";
            commands += afterLabel;
        }
        else if(operator == BinaryOperator.assign) { //todo
            Type firstType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
            String secondOperandCommands = binaryExpression.getSecondOperand().accept(this);
            if(firstType instanceof ListType) {
                //todo make new list with List copy constructor with the second operand commands
                // (add these commands to secondOperandCommands)
            }
            if(binaryExpression.getFirstOperand() instanceof Identifier) {
                commands += secondOperandCommands + "\n";
                Type expressionType = binaryExpression.getFirstOperand().accept(this.expressionTypeChecker);
                if (expressionType instanceof IntType || expressionType instanceof BoolType) {
                    commands += "istore ";
                } else {
                    commands += "astore ";
                }
                commands += this.slotOf(((Identifier) binaryExpression.getFirstOperand()).getName()) + "\n";
            }
            else if(binaryExpression.getFirstOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(binaryExpression.getFirstOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getInstance();
                Type memberType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(UnaryExpression unaryExpression) {
        UnaryOperator operator = unaryExpression.getOperator();
        String commands = "";
        if(operator == UnaryOperator.minus) {
            commands += unaryExpression.getOperand().accept(this);
            commands += "ineg\n";
        }
        else if(operator == UnaryOperator.not) {
            commands += "ldc 1\n";
            commands += unaryExpression.getOperand().accept(this);
            commands += "isub\n";
        }
        else if((operator == UnaryOperator.predec) || (operator == UnaryOperator.preinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                commands += "iinc " + this.slotOf(((Identifier) unaryExpression.getOperand()).getName()) +",1\n";
                unaryExpression.getOperand().accept(this);
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                commands += unaryExpression.getOperand().accept(this);
                commands += "ldc 1\n";
                commands += "iadd\n";
                // Todo: Check if casting is correct
                commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
                commands += "checkcast java/lang/Object\n";
                //
                commands += ((ListAccessByIndex)unaryExpression.getOperand()).getIndex().accept(this);
                commands += "invokevirtual List/setElement(ILjava/lang/Object;)V\n";
                commands += unaryExpression.getOperand().accept(this);
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {

                }
            }
        }
        else if((operator == UnaryOperator.postdec) || (operator == UnaryOperator.postinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                commands += unaryExpression.getOperand().accept(this);
                commands += "iinc " + this.slotOf(((Identifier) unaryExpression.getOperand()).getName()) +",1\n";
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                commands += unaryExpression.getOperand().accept(this);
                commands += "dup\n";
                commands += "ldc 1\n";
                commands += "iadd\n";
                // Todo: Check if casting is correct
                commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
                commands += "checkcast java/lang/Object\n";
                //
                commands += ((ListAccessByIndex)unaryExpression.getOperand()).getIndex().accept(this);
                commands += "invokevirtual List/setElement(ILjava/lang/Object;)V\n";
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(ObjectOrListMemberAccess objectOrListMemberAccess) {
        Type memberType = objectOrListMemberAccess.accept(expressionTypeChecker);
        Type instanceType = objectOrListMemberAccess.getInstance().accept(expressionTypeChecker);
        String memberName = objectOrListMemberAccess.getMemberName().getName();
        String commands = "";
        if(instanceType instanceof ClassType) {
            String className = ((ClassType) instanceType).getClassName().getName();
            try {
                SymbolTable classSymbolTable = ((ClassSymbolTableItem) SymbolTable.root.getItem(ClassSymbolTableItem.START_KEY + className, true)).getClassSymbolTable();
                try {
                    classSymbolTable.getItem(FieldSymbolTableItem.START_KEY + memberName, true);
                    //todo it is a field
                } catch (ItemNotFoundException memberIsMethod) {
                    //todo it is a method (new instance of Fptr)
                }
            } catch (ItemNotFoundException classNotFound) {
            }
        }
        else if(instanceType instanceof ListType) {
            //todo
        }
        return commands;
    }

    @Override
    public String visit(Identifier identifier) {
        String commands = "";
        commands += "aload " + this.slotOf(identifier.getName()) + "\n";
        Type expressionType = identifier.accept(this.expressionTypeChecker);
        if (expressionType instanceof IntType) {
            commands += "invokevirtual java/lang/Integer/intValue()I\n";
        } else if (expressionType instanceof BoolType) {
            commands += "invokevirtual java/lang/Boolean/booleanValue()Z\n";
        }
        return commands;
    }

    @Override
    public String visit(ListAccessByIndex listAccessByIndex) {
        String commands = "";
        commands += listAccessByIndex.accept(this) + "\n";
        commands += listAccessByIndex.getIndex().accept(this) + "\n";
        commands += "invokevirtual List/getElement(Ljava/lang/Integer;)Ljava/lang/Object\n";
        Type listAccessByIndexType = listAccessByIndex.accept(this.expressionTypeChecker);

        if (listAccessByIndexType instanceof IntType) { // Todo: Complete casting object to proper type

        } else if (listAccessByIndexType instanceof BoolType) {

        }
        return commands;
    }

    @Override
    public String visit(MethodCall methodCall) {
        String commands = "";
        commands += "new java/util/ArrayList\n";
        commands += "dup\n";
        commands += "invokespecial java/util/ArrayList/<init>()V\n";
        for (Expression expression : methodCall.getArgs()) {
            commands += expression.accept(this);

            Type expressionType = expression.accept(this.expressionTypeChecker);
            if (expressionType instanceof IntType) {
                commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
            } else if (expressionType instanceof BoolType) {
                commands += "invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;\n";
            }
            commands += "checkcast java/lang/Object\n";
            commands += "invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n";
            commands += "pop\n";
        }
        commands += methodCall.getInstance().accept(this) + "\n";
        commands += "invokevirtual Fptr/invoke(Ljava/util/ArrayList;)Ljava/lang/Object\n";
        // Todo: Complete casting objcet to proper type
        return commands;
    }

    @Override
    public String visit(NewClassInstance newClassInstance) {
        String args = "";

        String commands = "";
        commands += "new " + newClassInstance.getClassType().getClassName().getName() + "\n";
        commands += "dup\n";
        for (Expression expression : newClassInstance.getArgs()) {
            expression.accept(this);
            Type expressionType = expression.accept(this.expressionTypeChecker);
            if (expressionType instanceof IntType) {
                commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
            } else if (expressionType instanceof BoolType) {
                commands += "invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;\n";
            }
            args += this.makeTypeSignature(expressionType);
        }
        commands += "invokespecial " + newClassInstance.getClassType().getClassName().getName() + "/<init>("
                + args+ ")V";
        return commands;
    }

    @Override
    public String visit(ThisClass thisClass) {
        String commands = "";
        commands += "aload_0\n";
        return commands;
    }

    @Override
    public String visit(ListValue listValue) {
        String commands = "";
        commands += "new List\n";
        commands += "dup\n";

        commands += "new java/util/ArrayList\n";
        commands += "dup\n";
        commands += "invokespecial java/util/ArrayList/<init>()V\n";

        for (Expression expression : listValue.getElements()) {
            commands += "dup\n"; // Todo: Check why we need dup
            commands += expression.accept(this);
            Type expressionType = expression.accept(this.expressionTypeChecker);
            if (expressionType instanceof IntType) {
                commands += "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;\n";
            } else if (expressionType instanceof BoolType) {
                commands += "invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;\n";
            }
            commands += "checkcast java/lang/Object\n";
            commands += "invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n";
            commands += "pop\n";
        }

        commands += "invokespecial List/<init>(Ljava/util/ArrayList;)V\n";
        return commands;
    }

    @Override
    public String visit(NullValue nullValue) {
        String commands = "";
        commands += "ldc null\n";
        return commands;
    }

    @Override
    public String visit(IntValue intValue) {
        String commands = "ldc ";
        commands = commands + intValue.getConstant();
        commands = commands + "\n";
        return commands;
    }

    @Override
    public String visit(BoolValue boolValue) {
        String commands = "ldc ";
        if (boolValue.getConstant()){
            commands = commands + "1";
        }else{
            commands = commands + "0";
        }
        commands = commands + "\n";
        return commands;
    }

    @Override
    public String visit(StringValue stringValue) {
        String commands = "";
        commands = commands + "ldc ";
        commands = commands + "\"" + stringValue.getConstant() + "\"";
        commands = commands + "\n";
        return commands;
    }
}