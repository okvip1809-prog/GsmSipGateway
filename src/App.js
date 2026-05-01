import React, {memo, useState} from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  StyleSheet, Alert, NativeModules, ScrollView
} from 'react-native';

const {SipBridge} = NativeModules;

const Field = memo(function Field({label, value, onChangeText, secure, keyboardType}) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        blurOnSubmit={false}
        keyboardType={keyboardType || 'default'}
        placeholderTextColor="#555"
        placeholder={label}
      />
    </View>
  );
});

export default function App() {
  // Server config
  const [host, setHost] = useState('103.82.193.58');
  const [port, setPort] = useState('5060');
  const [answerRings, setAnswerRings] = useState('1');
  
  // SIM1 (1001)
  const [username1, setUsername1] = useState('1001');
  const [password1, setPassword1] = useState('abc12123');
  
  // SIM2 (1002)
  const [username2, setUsername2] = useState('1002');
  const [password2, setPassword2] = useState('abc12123');
  
  const [status, setStatus] = useState('Not configured');
  const [running, setRunning] = useState(false);

  const saveDualSipConfig = async () => {
    try {
      const result = await SipBridge.saveDualSipConfig({
        host: host.trim(),
        port: parseInt(port, 10) || 5060,
        username_sim1: username1.trim(),
        password_sim1: password1.trim(),
        username_sim2: username2.trim(),
        password_sim2: password2.trim(),
        answer_rings: parseInt(answerRings, 10) || 1,
      });
      setStatus('Running (Dual SIP)');
      setRunning(true);
      Alert.alert('Success', result);
    } catch (e) {
      setStatus('Error: ' + e.message);
      Alert.alert('Error', e.message);
    }
  };

  const stopGateway = async () => {
    try {
      const result = await SipBridge.stopService();
      setStatus('Stopped');
      setRunning(false);
      Alert.alert('Stopped', result);
    } catch (e) {
      Alert.alert('Error', e.message);
    }
  };

  return (
    <ScrollView
      style={styles.container}
      keyboardShouldPersistTaps="always"
      keyboardDismissMode="none"
    >
      <Text style={styles.title}>GSM SIP Gateway</Text>
      <Text style={styles.subtitle}>Dual SIM Support</Text>
      <View style={styles.statusBar}>
        <Text style={styles.statusText}>Status: {status}</Text>
      </View>

      {/* FreePBX Server Settings */}
      <Text style={styles.section}>📡 FreePBX Server Settings</Text>
      <Field 
        label="FreePBX IP Address" 
        value={host} 
        onChangeText={setHost} 
        keyboardType="numbers-and-punctuation" 
      />
      <Field 
        label="SIP Port" 
        value={port} 
        onChangeText={setPort} 
        keyboardType="numeric" 
      />
      <Field 
        label="Answer After Rings" 
        value={answerRings} 
        onChangeText={setAnswerRings} 
        keyboardType="numeric" 
      />

      {/* SIM1 Configuration */}
      <Text style={styles.section}>📱 SIM 1 (Slot 1)</Text>
      <Field 
        label="SIP Username (SIM1)" 
        value={username1} 
        onChangeText={setUsername1} 
      />
      <Field 
        label="SIP Password (SIM1)" 
        value={password1} 
        onChangeText={setPassword1} 
        secure 
      />

      {/* SIM2 Configuration */}
      <Text style={styles.section}>📱 SIM 2 (Slot 2)</Text>
      <Field 
        label="SIP Username (SIM2)" 
        value={username2} 
        onChangeText={setUsername2} 
      />
      <Field 
        label="SIP Password (SIM2)" 
        value={password2} 
        onChangeText={setPassword2} 
        secure 
      />

      {/* Control Buttons */}
      <TouchableOpacity style={styles.btn} onPress={saveDualSipConfig}>
        <Text style={styles.btnText}>💾 Save &amp; Start Dual SIP</Text>
      </TouchableOpacity>
      
      {running && (
        <TouchableOpacity style={styles.btnStop} onPress={stopGateway}>
          <Text style={styles.btnText}>⏹️ Stop Service</Text>
        </TouchableOpacity>
      )}

      {/* Information */}
      <View style={styles.howto}>
        <Text style={styles.howtoTitle}>ℹ️ How Dual SIM Works</Text>
        <Text style={styles.howtoItem}>🔵 SIM1 incoming call → Auto-answer</Text>
        <Text style={styles.howtoItem}>🔵 Bridge to SIP account 1001</Text>
        <Text style={styles.howtoItem}>🟠 SIM2 incoming call → Auto-answer</Text>
        <Text style={styles.howtoItem}>🟠 Bridge to SIP account 1002</Text>
        <Text style={styles.howtoItem}>✅ Both calls work simultaneously</Text>
      </View>

      <View style={styles.info}>
        <Text style={styles.infoTitle}>⚙️ Configuration Saved To</Text>
        <Text style={styles.infoText}>SharedPreferences: sip_config</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#0a0a0a', padding: 20},
  title: {color: '#fff', fontSize: 24, fontWeight: 'bold', marginTop: 40, marginBottom: 4},
  subtitle: {color: '#64748b', fontSize: 13, marginBottom: 16},
  statusBar: {backgroundColor: '#1a1a2e', padding: 12, borderRadius: 8, marginBottom: 24},
  statusText: {color: '#88aaff', fontSize: 14, fontWeight: '500'},
  section: {color: '#0ea5e9', fontSize: 13, textTransform: 'uppercase', letterSpacing: 1.2, marginBottom: 12, fontWeight: '600'},
  field: {marginBottom: 14},
  label: {color: '#94a3b8', marginBottom: 6, fontSize: 12, fontWeight: '500'},
  input: {backgroundColor: '#1e1e1e', color: '#fff', padding: 14, borderRadius: 8, borderWidth: 1, borderColor: '#2a2a2a', fontSize: 14},
  btn: {backgroundColor: '#059669', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 16, marginBottom: 12},
  btnStop: {backgroundColor: '#dc2626', padding: 16, borderRadius: 10, alignItems: 'center', marginTop: 0, marginBottom: 24},
  btnText: {color: '#fff', fontSize: 16, fontWeight: '700'},
  howto: {backgroundColor: '#111827', padding: 16, borderRadius: 10, marginBottom: 16},
  howtoTitle: {color: '#fbbf24', fontWeight: '700', marginBottom: 12, fontSize: 14},
  howtoItem: {color: '#9ca3af', marginBottom: 8, fontSize: 13, lineHeight: 20},
  info: {backgroundColor: '#1e293b', padding: 14, borderRadius: 8, marginBottom: 40},
  infoTitle: {color: '#cbd5e1', fontWeight: '600', marginBottom: 6, fontSize: 12},
  infoText: {color: '#64748b', fontSize: 12, fontFamily: 'monospace'},
});

