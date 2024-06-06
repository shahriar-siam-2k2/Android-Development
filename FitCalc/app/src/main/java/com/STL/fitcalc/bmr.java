package com.STL.fitcalc;

import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Objects;

public class bmr extends AppCompatActivity {

    private Spinner spinner;
    private EditText weightInput, heightFeetInput, heightInchInput, ageInput;
    private TextView resultText, activityText;
    private Button resultBtn;
    private RadioButton maleBtn, femaleBtn;
    private String gender;
    double weight, feet, inch, height, bmr, finalBMR;
    int age, spinnerPosition;
    boolean radio = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_bmr);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        spinner = (Spinner) findViewById(R.id.spinner);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                bmr.this,
                android.R.layout.simple_spinner_item,
                getResources().getStringArray(R.array.activities)
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        weightInput = findViewById(R.id.weightInput);
        heightFeetInput = findViewById(R.id.heightFeetInput);
        heightInchInput = findViewById(R.id.heightInchInput);
        ageInput = findViewById(R.id.ageInput);
        maleBtn = findViewById(R.id.male);
        femaleBtn = findViewById(R.id.female);
        resultBtn = findViewById(R.id.btnBMR);
        resultText = findViewById(R.id.resultText);
        activityText = findViewById(R.id.activityText);

        resultBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(maleBtn.isChecked()) {
                    gender = maleBtn.getText().toString();
                    radio = true;
                }
                else if(femaleBtn.isChecked()) {
                    gender = femaleBtn.getText().toString();
                    radio = true;
                }

                spinnerPosition = spinner.getSelectedItemPosition();

                if (TextUtils.isEmpty(weightInput.getText()) || TextUtils.isEmpty(heightFeetInput.getText()) || TextUtils.isEmpty(heightInchInput.getText()) || TextUtils.isEmpty(ageInput.getText()) || spinnerPosition == 0 || radio == false) {
                    showAlertDialog("Please fill out all fields.");
                }
                else {
                    weight = Double.parseDouble(weightInput.getText().toString());
                    feet = Double.parseDouble(heightFeetInput.getText().toString());
                    inch = Double.parseDouble(heightInchInput.getText().toString());
                    age = Integer.parseInt(ageInput.getText().toString());

                    height = (feet * 30.48) + (inch * 2.54); // converted to centimeter

                    if(gender.equals("Male")) {
                        bmr = 66 + (13.7 * weight) + (5 * height) - (6.8 * age);
                    }
                    else if(gender.equals("Female")) {
                        bmr = 655 + (9.6 * weight) + (1.8 * height) - (4.7 * age);
                    }

                    if(spinnerPosition == 1) {
                        finalBMR = bmr * 1.2;
                    }
                    else if(spinnerPosition == 2) {
                        finalBMR = bmr * 1.375;
                    }
                    else if(spinnerPosition == 3) {
                        finalBMR = bmr * 1.55;
                    }
                    else if(spinnerPosition == 4) {
                        finalBMR = bmr * 1.725;
                    }
                    else if(spinnerPosition == 5) {
                        finalBMR = bmr * 1.9;
                    }

                    resultText.setText("Your BMR is " + String.format("%.2f", bmr) + " Calories/day.");
                    activityText.setText("Your daily calorie requirement based on activity level is " + String.format("%.2f", finalBMR) + " Calories.");
                }
            }
        });
    }

    private void showAlertDialog(String s) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Empty Fields")
                .setMessage(s)
                .setPositiveButton("CLOSE", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                })
                .create();
        dialog.show();
    }
}