package com.swift.sandhook.xposedcompat.methodgen;

import android.text.TextUtils;

import com.android.dx.BinaryOp;
import com.android.dx.Code;
import com.android.dx.Comparison;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import static com.swift.sandhook.xposedcompat.methodgen.DexMakerUtils.autoBoxIfNecessary;
import static com.swift.sandhook.xposedcompat.methodgen.DexMakerUtils.autoUnboxIfNecessary;
import static com.swift.sandhook.xposedcompat.methodgen.DexMakerUtils.createResultLocals;
import static com.swift.sandhook.xposedcompat.methodgen.DexMakerUtils.getObjTypeIdIfPrimitive;
import static com.swift.sandhook.xposedcompat.methodgen.DexMakerUtils.moveException;

public class HookerDexMaker {

    public static final String METHOD_NAME_BACKUP = "backup";
    public static final String METHOD_NAME_HOOK = "hook";
    public static final String METHOD_NAME_CALL_BACKUP = "callBackup";
    public static final String METHOD_NAME_SETUP = "setup";
    public static final TypeId<Object[]> objArrayTypeId = TypeId.get(Object[].class);
    private static final String CLASS_DESC_PREFIX = "L";
    private static final String CLASS_NAME_PREFIX = "EdHooker";
    private static final String FIELD_NAME_HOOK_INFO = "additionalHookInfo";
    private static final String FIELD_NAME_METHOD = "method";
    private static final String PARAMS_FIELD_NAME_METHOD = "method";
    private static final String PARAMS_FIELD_NAME_THIS_OBJECT = "thisObject";
    private static final String PARAMS_FIELD_NAME_ARGS = "args";
    private static final String CALLBACK_METHOD_NAME_BEFORE = "callBeforeHookedMethod";
    private static final String CALLBACK_METHOD_NAME_AFTER = "callAfterHookedMethod";
    private static final String PARAMS_METHOD_NAME_IS_EARLY_RETURN = "isEarlyReturn";
    private static final TypeId<Throwable> throwableTypeId = TypeId.get(Throwable.class);
    private static final TypeId<Member> memberTypeId = TypeId.get(Member.class);
    private static final TypeId<XC_MethodHook> callbackTypeId = TypeId.get(XC_MethodHook.class);
    private static final TypeId<XposedBridge.AdditionalHookInfo> hookInfoTypeId
            = TypeId.get(XposedBridge.AdditionalHookInfo.class);
    private static final TypeId<XposedBridge.CopyOnWriteSortedSet> callbacksTypeId
            = TypeId.get(XposedBridge.CopyOnWriteSortedSet.class);
    private static final TypeId<XC_MethodHook.MethodHookParam> paramTypeId
            = TypeId.get(XC_MethodHook.MethodHookParam.class);
    private static final MethodId<XC_MethodHook.MethodHookParam, Void> setResultMethodId =
            paramTypeId.getMethod(TypeId.VOID, "setResult", TypeId.OBJECT);
    private static final MethodId<XC_MethodHook.MethodHookParam, Void> setThrowableMethodId =
            paramTypeId.getMethod(TypeId.VOID, "setThrowable", throwableTypeId);
    private static final MethodId<XC_MethodHook.MethodHookParam, Object> getResultMethodId =
            paramTypeId.getMethod(TypeId.OBJECT, "getResult");
    private static final MethodId<XC_MethodHook.MethodHookParam, Throwable> getThrowableMethodId =
            paramTypeId.getMethod(throwableTypeId, "getThrowable");
    private static final MethodId<XC_MethodHook.MethodHookParam, Boolean> hasThrowableMethodId =
            paramTypeId.getMethod(TypeId.BOOLEAN, "hasThrowable");
    private static final MethodId<XC_MethodHook, Void> callAfterCallbackMethodId =
            callbackTypeId.getMethod(TypeId.VOID, CALLBACK_METHOD_NAME_AFTER, paramTypeId);
    private static final MethodId<XC_MethodHook, Void> callBeforeCallbackMethodId =
            callbackTypeId.getMethod(TypeId.VOID, CALLBACK_METHOD_NAME_BEFORE, paramTypeId);
    private static final FieldId<XC_MethodHook.MethodHookParam, Boolean> returnEarlyFieldId =
            paramTypeId.getField(TypeId.BOOLEAN, "returnEarly");
    private static final TypeId<XposedBridge> xposedBridgeTypeId = TypeId.get(XposedBridge.class);
    private static final MethodId<XposedBridge, Void> logThrowableMethodId =
            xposedBridgeTypeId.getMethod(TypeId.VOID, "log", throwableTypeId);
    private static final MethodId<XposedBridge, Void> logStrMethodId =
            xposedBridgeTypeId.getMethod(TypeId.VOID, "log", TypeId.STRING);

    private static AtomicLong sClassNameSuffix = new AtomicLong(1);

    private FieldId<?, XposedBridge.AdditionalHookInfo> mHookInfoFieldId;
    private FieldId<?, Member> mMethodFieldId;
    private MethodId<?, ?> mBackupMethodId;
    private MethodId<?, ?> mCallBackupMethodId;
    private MethodId<?, ?> mHookMethodId;

    private TypeId<?> mHookerTypeId;
    private TypeId<?>[] mParameterTypeIds;
    private Class<?>[] mActualParameterTypes;
    private Class<?> mReturnType;
    private TypeId<?> mReturnTypeId;
    private boolean mIsStatic;
    // TODO use this to generate methods
    private boolean mHasThrowable;

    private DexMaker mDexMaker;
    private Member mMember;
    private XposedBridge.AdditionalHookInfo mHookInfo;
    private ClassLoader mAppClassLoader;
    private Class<?> mHookClass;
    private Method mHookMethod;
    private Method mBackupMethod;
    private Method mCallBackupMethod;
    private String mDexDirPath;

    private static TypeId<?>[] getParameterTypeIds(Class<?>[] parameterTypes, boolean isStatic) {
        int parameterSize = parameterTypes.length;
        int targetParameterSize = isStatic ? parameterSize : parameterSize + 1;
        TypeId<?>[] parameterTypeIds = new TypeId<?>[targetParameterSize];
        int offset = 0;
        if (!isStatic) {
            parameterTypeIds[0] = TypeId.OBJECT;
            offset = 1;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypeIds[i + offset] = TypeId.get(parameterTypes[i]);
        }
        return parameterTypeIds;
    }

    private static Class<?>[] getParameterTypes(Class<?>[] parameterTypes, boolean isStatic) {
        if (isStatic) {
            return parameterTypes;
        }
        int parameterSize = parameterTypes.length;
        int targetParameterSize = parameterSize + 1;
        Class<?>[] newParameterTypes = new Class<?>[targetParameterSize];
        int offset = 1;
        newParameterTypes[0] = Object.class;
        System.arraycopy(parameterTypes, 0, newParameterTypes, offset, parameterTypes.length);
        return newParameterTypes;
    }

    public void start(Member member, XposedBridge.AdditionalHookInfo hookInfo,
                      ClassLoader appClassLoader, String dexDirPath) throws Exception {
        if (member instanceof Method) {
            Method method = (Method) member;
            mIsStatic = Modifier.isStatic(method.getModifiers());
            mReturnType = method.getReturnType();
            if (mReturnType.equals(Void.class) || mReturnType.equals(void.class)
                    || mReturnType.isPrimitive()) {
                mReturnTypeId = TypeId.get(mReturnType);
            } else {
                // all others fallback to plain Object for convenience
                mReturnType = Object.class;
                mReturnTypeId = TypeId.OBJECT;
            }
            mParameterTypeIds = getParameterTypeIds(method.getParameterTypes(), mIsStatic);
            mActualParameterTypes = getParameterTypes(method.getParameterTypes(), mIsStatic);
            mHasThrowable = method.getExceptionTypes().length > 0;
        } else if (member instanceof Constructor) {
            Constructor constructor = (Constructor) member;
            mIsStatic = false;
            mReturnType = void.class;
            mReturnTypeId = TypeId.VOID;
            mParameterTypeIds = getParameterTypeIds(constructor.getParameterTypes(), mIsStatic);
            mActualParameterTypes = getParameterTypes(constructor.getParameterTypes(), mIsStatic);
            mHasThrowable = constructor.getExceptionTypes().length > 0;
        } else if (member.getDeclaringClass().isInterface()) {
            throw new IllegalArgumentException("Cannot hook interfaces: " + member.toString());
        } else if (Modifier.isAbstract(member.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook abstract methods: " + member.toString());
        } else {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + member.toString());
        }
        mMember = member;
        mHookInfo = hookInfo;
        mDexDirPath = dexDirPath;
        if (appClassLoader == null
                || appClassLoader.getClass().getName().equals("java.lang.BootClassLoader")) {
            mAppClassLoader = this.getClass().getClassLoader();
        } else {
            mAppClassLoader = appClassLoader;
        }
        doMake();
    }

    private void doMake() throws Exception {
        mDexMaker = new DexMaker();
        // Generate a Hooker class.
        String className = CLASS_NAME_PREFIX + sClassNameSuffix.getAndIncrement();
        String classDesc = CLASS_DESC_PREFIX + className + ";";
        mHookerTypeId = TypeId.get(classDesc);
        mDexMaker.declare(mHookerTypeId, className + ".generated", Modifier.PUBLIC, TypeId.OBJECT);
        generateFields();
        generateSetupMethod();
        generateBackupMethod();
        generateHookMethod();
        generateCallBackupMethod();

        ClassLoader loader;
        if (TextUtils.isEmpty(mDexDirPath)) {
            throw new IllegalArgumentException("dexDirPath should not be empty!!!");
        }
            // Create the dex file and load it.
        loader = mDexMaker.generateAndLoad(mAppClassLoader, new File(mDexDirPath));

        mHookClass = loader.loadClass(className);
        // Execute our newly-generated code in-process.
        mHookClass.getMethod(METHOD_NAME_SETUP, Member.class, XposedBridge.AdditionalHookInfo.class)
                .invoke(null, mMember, mHookInfo);
        mHookMethod = mHookClass.getMethod(METHOD_NAME_HOOK, mActualParameterTypes);
        mBackupMethod = mHookClass.getMethod(METHOD_NAME_BACKUP, mActualParameterTypes);
        mCallBackupMethod = mHookClass.getMethod(METHOD_NAME_CALL_BACKUP, mActualParameterTypes);
        //HookMain.backupAndHook(mMember, mHookMethod, mBackupMethod);
    }

    public Method getHookMethod() {
        return mHookMethod;
    }

    public Method getBackupMethod() {
        return mBackupMethod;
    }

    public Method getCallBackupMethod() {
        return mCallBackupMethod;
    }

    public Class getHookClass() {
        return mHookClass;
    }

    private void generateFields() {
        mHookInfoFieldId = mHookerTypeId.getField(hookInfoTypeId, FIELD_NAME_HOOK_INFO);
        mMethodFieldId = mHookerTypeId.getField(memberTypeId, FIELD_NAME_METHOD);
        mDexMaker.declare(mHookInfoFieldId, Modifier.STATIC, null);
        mDexMaker.declare(mMethodFieldId, Modifier.STATIC, null);
    }

    private void generateSetupMethod() {
        MethodId<?, Void> setupMethodId = mHookerTypeId.getMethod(
                TypeId.VOID, METHOD_NAME_SETUP, memberTypeId, hookInfoTypeId);
        Code code = mDexMaker.declare(setupMethodId, Modifier.PUBLIC | Modifier.STATIC);
        // init logic
        // get parameters
        Local<Member> method = code.getParameter(0, memberTypeId);
        Local<XposedBridge.AdditionalHookInfo> hookInfo = code.getParameter(1, hookInfoTypeId);
        // save params to static
        code.sput(mMethodFieldId, method);
        code.sput(mHookInfoFieldId, hookInfo);
        code.returnVoid();
    }

    private void generateBackupMethod() {
        mBackupMethodId = mHookerTypeId.getMethod(mReturnTypeId, METHOD_NAME_BACKUP, mParameterTypeIds);
        Code code = mDexMaker.declare(mBackupMethodId, Modifier.PUBLIC | Modifier.STATIC);
        Map<TypeId, Local> resultLocals = createResultLocals(code);
        // do nothing
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.returnVoid();
        } else {
            // we have limited the returnType to primitives or Object, so this should be safe
            code.returnValue(resultLocals.get(mReturnTypeId));
        }
    }

    private void generateCallBackupMethod() {
        mCallBackupMethodId = mHookerTypeId.getMethod(mReturnTypeId, METHOD_NAME_CALL_BACKUP, mParameterTypeIds);
        Code code = mDexMaker.declare(mCallBackupMethodId, Modifier.PUBLIC | Modifier.STATIC);
        // just call backup and return its result
        Local[] allArgsLocals = createParameterLocals(code);
        Map<TypeId, Local> resultLocals = createResultLocals(code);
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.invokeStatic(mBackupMethodId, null, allArgsLocals);
            code.returnVoid();
        } else {
            Local result = resultLocals.get(mReturnTypeId);
            code.invokeStatic(mBackupMethodId, result, allArgsLocals);
            code.returnValue(result);
        }
    }

    private void generateHookMethod() {
        mHookMethodId = mHookerTypeId.getMethod(mReturnTypeId, METHOD_NAME_HOOK, mParameterTypeIds);
        Code code = mDexMaker.declare(mHookMethodId, Modifier.PUBLIC | Modifier.STATIC);

        // code starts

        // prepare common labels
        Label noHookReturn = new Label();
        Label incrementAndCheckBefore = new Label();
        Label tryBeforeCatch = new Label();
        Label noExceptionBefore = new Label();
        Label checkAndCallBackup = new Label();
        Label beginCallBefore = new Label();
        Label beginCallAfter = new Label();
        Label tryOrigCatch = new Label();
        Label noExceptionOrig = new Label();
        Label tryAfterCatch = new Label();
        Label decrementAndCheckAfter = new Label();
        Label noBackupThrowable = new Label();
        Label throwThrowable = new Label();
        // prepare locals
        Local<Boolean> disableHooks = code.newLocal(TypeId.BOOLEAN);
        Local<XposedBridge.AdditionalHookInfo> hookInfo = code.newLocal(hookInfoTypeId);
        Local<XposedBridge.CopyOnWriteSortedSet> callbacks = code.newLocal(callbacksTypeId);
        Local<Object[]> snapshot = code.newLocal(objArrayTypeId);
        Local<Integer> snapshotLen = code.newLocal(TypeId.INT);
        Local<Object> callbackObj = code.newLocal(TypeId.OBJECT);
        Local<XC_MethodHook> callback = code.newLocal(callbackTypeId);

        Local<Object> resultObj = code.newLocal(TypeId.OBJECT); // as a temp Local
        Local<Integer> one = code.newLocal(TypeId.INT);
        Local<Object> nullObj = code.newLocal(TypeId.OBJECT);
        Local<Throwable> throwable = code.newLocal(throwableTypeId);

        Local<XC_MethodHook.MethodHookParam> param = code.newLocal(paramTypeId);
        Local<Member> method = code.newLocal(memberTypeId);
        Local<Object> thisObject = code.newLocal(TypeId.OBJECT);
        Local<Object[]> args = code.newLocal(objArrayTypeId);
        Local<Boolean> returnEarly = code.newLocal(TypeId.BOOLEAN);

        Local<Integer> actualParamSize = code.newLocal(TypeId.INT);
        Local<Integer> argIndex = code.newLocal(TypeId.INT);

        Local<Integer> beforeIdx = code.newLocal(TypeId.INT);
        Local<Object> lastResult = code.newLocal(TypeId.OBJECT);
        Local<Throwable> lastThrowable = code.newLocal(throwableTypeId);
        Local<Boolean> hasThrowable = code.newLocal(TypeId.BOOLEAN);

        Local[] allArgsLocals = createParameterLocals(code);

        Map<TypeId, Local> resultLocals = createResultLocals(code);

        code.loadConstant(args, null);
        code.loadConstant(argIndex, 0);
        code.loadConstant(one, 1);
        code.loadConstant(snapshotLen, 0);
        code.loadConstant(nullObj, null);

        // check XposedBridge.disableHooks flag

        FieldId<XposedBridge, Boolean> disableHooksField =
                xposedBridgeTypeId.getField(TypeId.BOOLEAN, "disableHooks");
        code.sget(disableHooksField, disableHooks);
        // disableHooks == true => no hooking
        code.compareZ(Comparison.NE, noHookReturn, disableHooks);

        // check callbacks length
        code.sget(mHookInfoFieldId, hookInfo);
        code.iget(hookInfoTypeId.getField(callbacksTypeId, "callbacks"), callbacks, hookInfo);
        code.invokeVirtual(callbacksTypeId.getMethod(objArrayTypeId, "getSnapshot"), snapshot, callbacks);
        code.arrayLength(snapshotLen, snapshot);
        // snapshotLen == 0 => no hooking
        code.compareZ(Comparison.EQ, noHookReturn, snapshotLen);

        // start hooking

        // prepare hooking locals
        int paramsSize = mParameterTypeIds.length;
        int offset = 0;
        // thisObject
        if (mIsStatic) {
            // thisObject = null
            code.loadConstant(thisObject, null);
        } else {
            // thisObject = args[0]
            offset = 1;
            code.move(thisObject, allArgsLocals[0]);
        }
        // actual args (exclude thisObject if this is not a static method)
        code.loadConstant(actualParamSize, paramsSize - offset);
        code.newArray(args, actualParamSize);
        for (int i = offset; i < paramsSize; i++) {
            Local parameter = allArgsLocals[i];
            // save parameter to resultObj as Object
            autoBoxIfNecessary(code, resultObj, parameter);
            code.loadConstant(argIndex, i - offset);
            // save Object to args
            code.aput(args, argIndex, resultObj);
        }
        // create param
        code.newInstance(param, paramTypeId.getConstructor());
        // set method, thisObject, args
        code.sget(mMethodFieldId, method);
        code.iput(paramTypeId.getField(memberTypeId, "method"), param, method);
        code.iput(paramTypeId.getField(TypeId.OBJECT, "thisObject"), param, thisObject);
        code.iput(paramTypeId.getField(objArrayTypeId, "args"), param, args);

        // call beforeCallbacks
        code.loadConstant(beforeIdx, 0);

        code.mark(beginCallBefore);
        // start of try
        code.addCatchClause(throwableTypeId, tryBeforeCatch);

        code.aget(callbackObj, snapshot, beforeIdx);
        code.cast(callback, callbackObj);
        code.invokeVirtual(callBeforeCallbackMethodId, null, callback, param);
        code.jump(noExceptionBefore);

        // end of try
        code.removeCatchClause(throwableTypeId);

        // start of catch
        code.mark(tryBeforeCatch);
        moveException(code, throwable);
        code.invokeStatic(logThrowableMethodId, null, throwable);
        code.invokeVirtual(setResultMethodId, null, param, nullObj);
        code.loadConstant(returnEarly, false);
        code.iput(returnEarlyFieldId, param, returnEarly);
        code.jump(incrementAndCheckBefore);

        // no exception when calling beforeCallbacks
        code.mark(noExceptionBefore);
        code.iget(returnEarlyFieldId, returnEarly, param);
        // if returnEarly == false, continue
        code.compareZ(Comparison.EQ, incrementAndCheckBefore, returnEarly);
        // returnEarly == true, break
        code.op(BinaryOp.ADD, beforeIdx, beforeIdx, one);
        code.jump(checkAndCallBackup);

        // increment and check to continue
        code.mark(incrementAndCheckBefore);
        code.op(BinaryOp.ADD, beforeIdx, beforeIdx, one);
        code.compare(Comparison.LT, beginCallBefore, beforeIdx, snapshotLen);

        // check and call backup
        code.mark(checkAndCallBackup);
        code.iget(returnEarlyFieldId, returnEarly, param);
        // if returnEarly == true, go to call afterCallbacks directly
        code.compareZ(Comparison.NE, noExceptionOrig, returnEarly);
        // try to call backup
        // try start
        code.addCatchClause(throwableTypeId, tryOrigCatch);
        // we have to load args[] to paramLocals
        // because args[] may be changed in beforeHookedMethod
        // should consider first param is thisObj if hooked method is not static
        offset = mIsStatic ? 0 : 1;
        for (int i = offset; i < allArgsLocals.length; i++) {
            code.loadConstant(argIndex, i - offset);
            code.aget(resultObj, args, argIndex);
            autoUnboxIfNecessary(code, allArgsLocals[i], resultObj, resultLocals, true);
        }
        // get pre-created Local with a matching typeId
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.invokeStatic(mBackupMethodId, null, allArgsLocals);
            // TODO maybe keep preset result to do some magic?
            code.invokeVirtual(setResultMethodId, null, param, nullObj);
        } else {
            Local returnedResult = resultLocals.get(mReturnTypeId);
            code.invokeStatic(mBackupMethodId, returnedResult, allArgsLocals);
            // save returnedResult to resultObj as a Object
            autoBoxIfNecessary(code, resultObj, returnedResult);
            // save resultObj to param
            code.invokeVirtual(setResultMethodId, null, param, resultObj);
        }
        // go to call afterCallbacks
        code.jump(noExceptionOrig);
        // try end
        code.removeCatchClause(throwableTypeId);
        // catch
        code.mark(tryOrigCatch);
        moveException(code, throwable);
        // exception occurred when calling backup, save throwable to param
        code.invokeVirtual(setThrowableMethodId, null, param, throwable);

        code.mark(noExceptionOrig);
        code.op(BinaryOp.SUBTRACT, beforeIdx, beforeIdx, one);

        // call afterCallbacks
        code.mark(beginCallAfter);
        // save results of backup calling
        code.invokeVirtual(getResultMethodId, lastResult, param);
        code.invokeVirtual(getThrowableMethodId, lastThrowable, param);
        // try start
        code.addCatchClause(throwableTypeId, tryAfterCatch);
        code.aget(callbackObj, snapshot, beforeIdx);
        code.cast(callback, callbackObj);
        code.invokeVirtual(callAfterCallbackMethodId, null, callback, param);
        // all good, just continue
        code.jump(decrementAndCheckAfter);
        // try end
        code.removeCatchClause(throwableTypeId);
        // catch
        code.mark(tryAfterCatch);
        moveException(code, throwable);
        code.invokeStatic(logThrowableMethodId, null, throwable);
        // if lastThrowable == null, go to recover lastResult
        code.compareZ(Comparison.EQ, noBackupThrowable, lastThrowable);
        // lastThrowable != null, recover lastThrowable
        code.invokeVirtual(setThrowableMethodId, null, param, lastThrowable);
        // continue
        code.jump(decrementAndCheckAfter);
        code.mark(noBackupThrowable);
        // recover lastResult and continue
        code.invokeVirtual(setResultMethodId, null, param, lastResult);
        // decrement and check continue
        code.mark(decrementAndCheckAfter);
        code.op(BinaryOp.SUBTRACT, beforeIdx, beforeIdx, one);
        code.compareZ(Comparison.GE, beginCallAfter, beforeIdx);

        // callbacks end
        // return
        code.invokeVirtual(hasThrowableMethodId, hasThrowable, param);
        // if hasThrowable, throw the throwable and return
        code.compareZ(Comparison.NE, throwThrowable, hasThrowable);
        // return getResult
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.returnVoid();
        } else {
            // getResult always return an Object, so save to resultObj
            code.invokeVirtual(getResultMethodId, resultObj, param);
            // have to unbox it if returnType is primitive
            // casting Object
            TypeId objTypeId = getObjTypeIdIfPrimitive(mReturnTypeId);
            Local matchObjLocal = resultLocals.get(objTypeId);
            code.cast(matchObjLocal, resultObj);
            // have to use matching typed Object(Integer, Double ...) to do unboxing
            Local toReturn = resultLocals.get(mReturnTypeId);
            autoUnboxIfNecessary(code, toReturn, matchObjLocal, resultLocals, true);
            // return
            code.returnValue(toReturn);
        }
        // throw throwable
        code.mark(throwThrowable);
        code.invokeVirtual(getThrowableMethodId, throwable, param);
        code.throwValue(throwable);

        // call backup and return
        code.mark(noHookReturn);
        if (mReturnTypeId.equals(TypeId.VOID)) {
            code.invokeStatic(mBackupMethodId, null, allArgsLocals);
            code.returnVoid();
        } else {
            Local result = resultLocals.get(mReturnTypeId);
            code.invokeStatic(mBackupMethodId, result, allArgsLocals);
            code.returnValue(result);
        }
    }

    private Local[] createParameterLocals(Code code) {
        Local[] paramLocals = new Local[mParameterTypeIds.length];
        for (int i = 0; i < mParameterTypeIds.length; i++) {
            paramLocals[i] = code.getParameter(i, mParameterTypeIds[i]);
        }
        return paramLocals;
    }
}
